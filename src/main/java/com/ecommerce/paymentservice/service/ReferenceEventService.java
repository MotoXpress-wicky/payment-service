package com.ecommerce.paymentservice.service;

import com.ecommerce.paymentservice.config.EventsProperties;
import com.ecommerce.paymentservice.config.EventsProperties.Action;
import com.ecommerce.paymentservice.entity.Payment;
import com.ecommerce.paymentservice.entity.PaymentMethod;
import com.ecommerce.paymentservice.entity.PaymentStatus;
import com.ecommerce.paymentservice.entity.ProcessedMessage;
import com.ecommerce.paymentservice.event.EventPublisher;
import com.ecommerce.paymentservice.event.PaymentEvents;
import com.ecommerce.paymentservice.listener.InvalidEventException;
import com.ecommerce.paymentservice.repository.PaymentRepository;
import com.ecommerce.paymentservice.repository.ProcessedMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Handles the events from the other services. It knows only generic actions (CREATE, SET_SHIPMENT, COLLECT, CANCEL);
 * which event name means which action is set in application.yaml.
 *
 * What each action does (docs/mvp/05-components/payment-service.md):
 *   CREATE        new payment, PENDING
 *   SET_SHIPMENT  save courier and tracking number (only while PENDING)
 *   COLLECT       PENDING to COLLECTED, publishes payment.collected
 *   CANCEL        PENDING to CANCELLED, publishes payment.cancelled
 *
 * The messageId is written to processed_messages in the SAME transaction as the change, so a message that
 * arrives twice does nothing the second time. A move that the state diagram does not allow is ignored and logged.
 */
@Slf4j
@Service
public class ReferenceEventService {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private final Map<String, Action> actions = new HashMap<>();
    private final PaymentRepository payments;
    private final ProcessedMessageRepository processed;
    private final PaymentNumberGenerator numbers;
    private final EventPublisher events;

    public ReferenceEventService(EventsProperties properties, PaymentRepository payments,
                                 ProcessedMessageRepository processed, PaymentNumberGenerator numbers,
                                 EventPublisher events) {
        properties.listen().forEach(listen -> actions.put(listen.name(), listen.action()));
        this.payments = payments;
        this.processed = processed;
        this.numbers = numbers;
        this.events = events;
    }

    @Transactional
    public void handle(UUID messageId, String eventName, Map<String, Object> body) {
        Action action = actions.get(eventName);
        if (action == null) {
            throw new InvalidEventException("Event '" + eventName + "' is not one this service listens to");
        }
        if (body == null) {
            throw new InvalidEventException("Event '" + eventName + "' has no body");
        }
        if (processed.existsById(messageId)) {
            log.info("Skipped '{}' (message {}): already handled", eventName, messageId);
            return;
        }

        String reference = text(body, "reference", eventName);
        switch (action) {
            case CREATE -> create(reference, body, eventName);
            case SET_SHIPMENT -> setShipment(reference, body, eventName);
            case COLLECT -> collect(reference, eventName);
            case CANCEL -> cancel(reference, eventName);
        }

        // Same transaction as the change above: either both are saved or neither.
        processed.save(new ProcessedMessage(messageId));
    }

    // ---------- actions ----------

    private void create(String reference, Map<String, Object> body, String eventName) {
        if (payments.findByReferenceForUpdate(reference).isPresent()) {
            log.info("A payment for reference {} already exists. Nothing to create.", reference);
            return;
        }

        Payment payment = new Payment();
        payment.setPaymentNo(numbers.next());
        payment.setReference(reference);
        payment.setMethod(method(body, eventName));
        payment.setAmount(amount(body, eventName));
        payment.setCurrency(currency(body, eventName));
        payments.save(payment);
        log.info("Created payment {} for reference {}", payment.getPaymentNo(), reference);
    }

    private void setShipment(String reference, Map<String, Object> body, String eventName) {
        Payment payment = find(reference, eventName);
        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.warn("Ignored courier details for reference {}: the payment is {}", reference, payment.getStatus());
            return;
        }
        payment.setCourier(text(body, "courier", eventName));
        payment.setTrackingNumber(text(body, "trackingNumber", eventName));
    }

    private void collect(String reference, String eventName) {
        Payment payment = find(reference, eventName);
        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.warn("Ignored 'collect' for reference {}: the payment is already {}", reference, payment.getStatus());
            return;
        }
        payment.setStatus(PaymentStatus.COLLECTED);
        payment.setCollectedAt(Instant.now());
        events.publish(PaymentEvents.COLLECTED, PaymentEvents.collected(payment));
        log.info("Payment {} is COLLECTED", payment.getPaymentNo());
    }

    private void cancel(String reference, String eventName) {
        Payment payment = find(reference, eventName);
        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.warn("Ignored 'cancel' for reference {}: the payment is already {}", reference, payment.getStatus());
            return;
        }
        payment.setStatus(PaymentStatus.CANCELLED);
        payment.setCancelledAt(Instant.now());
        events.publish(PaymentEvents.CANCELLED, PaymentEvents.cancelled(payment));
        log.info("Payment {} is CANCELLED", payment.getPaymentNo());
    }

    // ---------- reading the event body ----------

    private Payment find(String reference, String eventName) {
        return payments.findByReferenceForUpdate(reference)
                .orElseThrow(() -> new InvalidEventException("Event '" + eventName + "' is for an unknown reference"));
    }

    private String text(Map<String, Object> body, String field, String eventName) {
        Object value = body.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new InvalidEventException("Event '" + eventName + "' has no '" + field + "'");
        }
        return String.valueOf(value).trim();
    }

    private PaymentMethod method(Map<String, Object> body, String eventName) {
        try {
            return PaymentMethod.valueOf(text(body, "paymentMethod", eventName));
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException("Event '" + eventName + "' has a payment method this service does not know");
        }
    }

    private BigDecimal amount(Map<String, Object> body, String eventName) {
        try {
            BigDecimal amount = new BigDecimal(text(body, "total", eventName));
            if (amount.signum() < 0 || amount.scale() > 2) {
                throw new NumberFormatException();
            }
            return amount;
        } catch (NumberFormatException e) {
            throw new InvalidEventException("Event '" + eventName + "' has a bad 'total'");
        }
    }

    private String currency(Map<String, Object> body, String eventName) {
        String currency = text(body, "currency", eventName);
        if (!CURRENCY.matcher(currency).matches()) {
            throw new InvalidEventException("Event '" + eventName + "' has a bad 'currency'");
        }
        return currency;
    }
}

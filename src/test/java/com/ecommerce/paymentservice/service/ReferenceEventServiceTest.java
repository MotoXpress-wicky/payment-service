package com.ecommerce.paymentservice.service;

import com.ecommerce.paymentservice.entity.Payment;
import com.ecommerce.paymentservice.entity.PaymentStatus;
import com.ecommerce.paymentservice.listener.InvalidEventException;
import com.ecommerce.paymentservice.repository.PaymentRepository;
import com.ecommerce.paymentservice.repository.ProcessedMessageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Not @Transactional: the change and its event must really commit. RabbitMQ is faked. */
@SpringBootTest
class ReferenceEventServiceTest {

    @Autowired
    ReferenceEventService service;
    @Autowired
    PaymentRepository payments;
    @Autowired
    ProcessedMessageRepository processed;

    @MockitoBean
    RabbitTemplate rabbit;

    @AfterEach
    void clean() {
        payments.deleteAll();
        processed.deleteAll();
    }

    private Map<String, Object> placedBody(String reference) {
        Map<String, Object> body = new HashMap<>();
        body.put("reference", reference);
        body.put("paymentMethod", "COD");
        body.put("total", "9500.00");
        body.put("currency", "LKR");
        return body;
    }

    private void send(String event, Map<String, Object> body) {
        service.handle(UUID.randomUUID(), event, body);
    }

    private Payment payment(String reference) {
        return payments.findAll().stream().filter(p -> p.getReference().equals(reference)).findFirst().orElseThrow();
    }

    @Test
    void aNewReferenceCreatesAPendingPayment() {
        send("order.placed", placedBody("MX-1"));

        Payment payment = payments.findAll().get(0);
        assertThat(payment.getReference()).isEqualTo("MX-1");
        assertThat(payment.getPaymentNo()).matches("PAY-\\d{4,}");
        assertThat(payment.getAmount()).isEqualByComparingTo("9500.00");
        assertThat(payment.getCurrency()).isEqualTo("LKR");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void theSameMessageTwiceIsHandledOnce() {
        UUID id = UUID.randomUUID();

        service.handle(id, "order.placed", placedBody("MX-1"));
        service.handle(id, "order.placed", placedBody("MX-1"));

        assertThat(payments.count()).isEqualTo(1);
        assertThat(processed.count()).isEqualTo(1);
    }

    @Test
    void aSecondCreateForTheSameReferenceChangesNothing() {
        send("order.placed", placedBody("MX-1"));
        send("order.placed", placedBody("MX-1"));

        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    void shippedSavesTheCourierAndTrackingNumber() {
        send("order.placed", placedBody("MX-1"));

        send("order.shipped", Map.of("reference", "MX-1", "courier", "Courier A", "trackingNumber", "WB55310"));

        Payment payment = payment("MX-1");
        assertThat(payment.getCourier()).isEqualTo("Courier A");
        assertThat(payment.getTrackingNumber()).isEqualTo("WB55310");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void deliveredCollectsThePaymentAndPublishesPaymentCollected() {
        send("order.placed", placedBody("MX-1"));

        send("order.delivered", Map.of("reference", "MX-1"));

        assertThat(payment("MX-1").getStatus()).isEqualTo(PaymentStatus.COLLECTED);
        assertThat(payment("MX-1").getCollectedAt()).isNotNull();

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(rabbit).convertAndSend(eq("app.events"), eq("payment.collected"), body.capture(), any(MessagePostProcessor.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) body.getValue();
        assertThat(event).containsEntry("reference", "MX-1").containsEntry("amount", "9500.00")
                .containsEntry("currency", "LKR").containsEntry("paymentId", payment("MX-1").getPaymentNo());
        assertThat(event.get("collectedAt")).isNotNull();
    }

    @Test
    void cancelledAndReturnedCancelThePaymentAndPublishPaymentCancelled() {
        send("order.placed", placedBody("MX-1"));
        send("order.placed", placedBody("MX-2"));

        send("order.cancelled", Map.of("reference", "MX-1"));
        send("order.returned", Map.of("reference", "MX-2"));

        assertThat(payment("MX-1").getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(payment("MX-2").getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(rabbit, times(2)).convertAndSend(eq("app.events"), eq("payment.cancelled"), any(Object.class), any(MessagePostProcessor.class));
    }

    @Test
    void aMoveThatTheDiagramDoesNotAllowIsIgnoredAndPublishesNothing() {
        send("order.placed", placedBody("MX-1"));
        send("order.delivered", Map.of("reference", "MX-1"));

        // collected money cannot be cancelled, and a second 'delivered' changes nothing
        send("order.cancelled", Map.of("reference", "MX-1"));
        send("order.delivered", Map.of("reference", "MX-1"));

        assertThat(payment("MX-1").getStatus()).isEqualTo(PaymentStatus.COLLECTED);
        verify(rabbit, times(1)).convertAndSend(eq("app.events"), eq("payment.collected"), any(Object.class), any(MessagePostProcessor.class));
        verify(rabbit, never()).convertAndSend(eq("app.events"), eq("payment.cancelled"), any(Object.class), any(MessagePostProcessor.class));
    }

    @Test
    void courierDetailsAfterCollectionAreIgnored() {
        send("order.placed", placedBody("MX-1"));
        send("order.delivered", Map.of("reference", "MX-1"));

        send("order.shipped", Map.of("reference", "MX-1", "courier", "Courier A", "trackingNumber", "LATE"));

        assertThat(payment("MX-1").getTrackingNumber()).isNull();
    }

    // ---------- messages that can never work go to the dead-letter queue ----------

    @Test
    void unknownReferenceIsRejected() {
        assertThatThrownBy(() -> send("order.delivered", Map.of("reference", "MX-404")))
                .isInstanceOf(InvalidEventException.class);
        assertThat(processed.count()).as("nothing remembered, so a person can fix it and replay").isZero();
    }

    @Test
    void unknownEventNameIsRejected() {
        assertThatThrownBy(() -> send("something.else", Map.of("reference", "MX-1"))).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void badBodiesAreRejected() {
        Map<String, Object> noReference = placedBody("MX-1");
        noReference.remove("reference");
        Map<String, Object> badTotal = placedBody("MX-1");
        badTotal.put("total", "nine thousand");
        Map<String, Object> threeDecimals = placedBody("MX-1");
        threeDecimals.put("total", "9500.005");
        Map<String, Object> badCurrency = placedBody("MX-1");
        badCurrency.put("currency", "lkr");
        Map<String, Object> badMethod = placedBody("MX-1");
        badMethod.put("paymentMethod", "BITCOIN");

        for (Map<String, Object> body : java.util.List.of(noReference, badTotal, threeDecimals, badCurrency, badMethod)) {
            assertThatThrownBy(() -> send("order.placed", body)).isInstanceOf(InvalidEventException.class);
        }
        assertThat(payments.count()).isZero();
    }

    @Test
    void shippedWithoutTrackingNumberIsRejected() {
        send("order.placed", placedBody("MX-1"));

        assertThatThrownBy(() -> send("order.shipped", Map.of("reference", "MX-1", "courier", "Courier A")))
                .isInstanceOf(InvalidEventException.class);
        verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class));
    }
}

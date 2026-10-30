package com.ecommerce.paymentservice.event;

import com.ecommerce.paymentservice.config.filter.RequestIdFilter;
import com.ecommerce.paymentservice.entity.OutboxEvent;
import com.ecommerce.paymentservice.repository.OutboxEventRepository;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/**
 * Announces events with an OUTBOX (M46, replaces the "lost if RabbitMQ is down" limit of M19).
 * <p>
 * publish() does not talk to RabbitMQ. It saves the event in the table outbox_events, in the SAME transaction as
 * the change it announces. So the change and its event are saved together or not at all, and an event of a rolled
 * back change never exists.
 * <p>
 * After the commit, OutboxRelay sends the waiting events at once. If RabbitMQ is down, they wait in the table and a
 * scheduled retry sends them later. The request of the user is never broken by RabbitMQ.
 * <p>
 * Without a transaction, publish() saves the row and sends at once.
 */
@Component
public class EventPublisher {

    private final OutboxEventRepository outbox;
    private final ObjectMapper json;
    private final ApplicationEventPublisher spring;
    private final OutboxRelay relay;

    public EventPublisher(OutboxEventRepository outbox, ObjectMapper json, ApplicationEventPublisher spring, OutboxRelay relay) {
        this.outbox = outbox;
        this.json = json;
        this.spring = spring;
        this.relay = relay;
    }

    /** The name is the routing key, for example "payment.collected". */
    public void publish(String eventName, Map<String, Object> body) {
        // The id is also the messageId header. A retry sends the same id, so listeners can skip a duplicate.
        outbox.save(new OutboxEvent(UUID.randomUUID(), eventName, json.writeValueAsString(body), MDC.get(RequestIdFilter.MDC_KEY)));
        spring.publishEvent(new SendNow());
    }

    /** Runs after the commit of the transaction that saved the event (or at once without a transaction). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void sendNow(SendNow ignored) {
        relay.flush();
    }

    /** "Something was saved in the outbox": a signal that waits inside Spring until the commit. */
    record SendNow() {
    }
}

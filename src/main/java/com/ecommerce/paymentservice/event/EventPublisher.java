package com.ecommerce.paymentservice.event;

import com.ecommerce.paymentservice.config.EventsProperties;
import com.ecommerce.paymentservice.config.filter.RequestIdFilter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.UUID;

/**
 * Publishes events to RabbitMQ, but only AFTER the database transaction has committed (M19).
 * We never announce something that was rolled back.
 *
 * How: publish() only hands a small object to Spring. The method send() waits for the commit
 * (TransactionalEventListener) and then sends. Without a transaction it sends at once.
 *
 * Known limit (M19, accepted for the MVP): if RabbitMQ is down at that moment, the event is lost
 * and only an error is logged. A reliable "outbox" table can replace this later.
 */
@Slf4j
@Component
public class EventPublisher {

    private final ApplicationEventPublisher spring;
    private final RabbitTemplate rabbit;
    private final String exchange;

    public EventPublisher(ApplicationEventPublisher spring, RabbitTemplate rabbit, EventsProperties events) {
        this.spring = spring;
        this.rabbit = rabbit;
        this.exchange = events.exchange();
    }

    /** The name is the routing key, for example "payment.collected". */
    public void publish(String eventName, Map<String, Object> body) {
        spring.publishEvent(new PendingEvent(eventName, UUID.randomUUID(), MDC.get(RequestIdFilter.MDC_KEY), body));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void send(PendingEvent event) {
        try {
            rabbit.convertAndSend(exchange, event.name(), event.body(), message -> {
                message.getMessageProperties().setHeader("messageId", event.messageId().toString());
                if (event.traceId() != null) {
                    message.getMessageProperties().setHeader("traceId", event.traceId());
                }
                return message;
            });
            // The body is not logged: it holds names and addresses.
            log.info("Published event '{}' (message {})", event.name(), event.messageId());
        } catch (AmqpException e) {
            log.error("Could not publish event '{}' (message {}). It is lost.", event.name(), event.messageId(), e);
        }
    }

    /** Waits inside Spring until the transaction commits. */
    record PendingEvent(String name, UUID messageId, String traceId, Map<String, Object> body) {
    }
}

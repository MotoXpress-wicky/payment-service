package com.ecommerce.paymentservice.event;

import com.ecommerce.paymentservice.config.EventsProperties;
import com.ecommerce.paymentservice.config.OutboxProperties;
import com.ecommerce.paymentservice.entity.OutboxEvent;
import com.ecommerce.paymentservice.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sends the events of the outbox to RabbitMQ (M46).
 * <p>
 * flush() takes the waiting events, oldest first, publishes each one and marks it sent. It stops at the first
 * failure (RabbitMQ is probably down), so a later event never overtakes an older one. The events stay in the table and are tried again by the scheduled retry.
 * <p>
 * It runs right after every commit (see EventPublisher) and again every few seconds. A lock makes the rounds of
 * one instance follow each other. A message can still be sent twice (for example if the service stops between
 * "published" and "marked sent", or with two instances). That is fine: every listener skips message ids it has
 * already handled.
 */
@Slf4j
@Component
public class OutboxRelay {

    private static final TypeReference<Map<String, Object>> BODY = new TypeReference<>() {
    };
    private static final int MAX_ERROR_LENGTH = 500;

    private final OutboxEventRepository outbox;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final String exchange;
    private final OutboxProperties properties;
    private final ReentrantLock lock = new ReentrantLock();

    public OutboxRelay(OutboxEventRepository outbox, RabbitTemplate rabbit, ObjectMapper json,
                       PlatformTransactionManager transactionManager, EventsProperties events, OutboxProperties properties) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.json = json;
        // Every write here runs in its OWN new transaction. This matters: flush() is called right after a commit,
        // and in that phase the finished transaction is still attached to the thread. A template that simply
        // joins it ("required") would write into a transaction that is already over, and the write would be lost.
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.exchange = events.exchange();
        this.properties = properties;
    }

    /** Sends the waiting events, oldest first, until one fails or the batch is done. Returns how many were sent. */
    public int flush() {
        lock.lock();
        try {
            int sent = 0;
            List<OutboxEvent> waiting = outbox.findWaiting(PageRequest.of(0, properties.batchSize()));
            for (OutboxEvent event : waiting) {
                Boolean ok = send(event);
                if (ok == null) {
                    continue;      // this one can never be sent (bad JSON). Do not block the others behind it.
                }
                if (!ok) {
                    break;         // RabbitMQ is not answering. Keep the sequence, try again later.
                }
                sent++;
            }
            return sent;
        } finally {
            lock.unlock();
        }
    }

    /** true = sent, false = RabbitMQ problem (stop), null = the stored event is broken (skip it). */
    private Boolean send(OutboxEvent event) {
        Map<String, Object> body;
        try {
            body = json.readValue(event.getBody(), BODY);
        } catch (JacksonException e) {
            log.error("Outbox event {} ('{}') holds no valid JSON and is skipped", event.getId(), event.getEventName(), e);
            markFailed(event, "invalid JSON: " + e.getOriginalMessage());
            return null;
        }
        try {
            rabbit.convertAndSend(exchange, event.getEventName(), body, message -> {
                message.getMessageProperties().setHeader("messageId", event.getId().toString());
                if (event.getTraceId() != null) {
                    message.getMessageProperties().setHeader("traceId", event.getTraceId());
                }
                return message;
            });
        } catch (AmqpException e) {
            log.error("Could not publish event '{}' (message {}). It stays in the outbox and is tried again.",
                    event.getEventName(), event.getId(), e);
            markFailed(event, e.getMessage());
            return false;
        }
        transaction.executeWithoutResult(status -> outbox.markSent(event.getId(), Instant.now()));
        // The body is not logged: it holds names and addresses.
        log.info("Published event '{}' (message {})", event.getEventName(), event.getId());
        return true;
    }

    private void markFailed(OutboxEvent event, String error) {
        String shortError = error == null ? "" : error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        transaction.executeWithoutResult(status -> outbox.markFailed(event.getId(), shortError));
    }

    /** Background retry: sends what is still waiting, for example after RabbitMQ was down. */
    @Scheduled(fixedDelayString = "${events.outbox.retry-interval:10s}", initialDelayString = "${events.outbox.retry-interval:10s}")
    public void retryWaiting() {
        int sent = flush();
        if (sent > 0) {
            log.info("Outbox retry: sent {} waiting event(s)", sent);
        }
    }

    /** Once a day: sent events are only kept for a while (events.outbox.keep). */
    @Scheduled(cron = "0 17 3 * * *")
    public void deleteOld() {
        Instant before = Instant.now().minus(properties.keep());
        Integer deleted = transaction.execute(status -> outbox.deleteSentBefore(before));
        if (deleted != null && deleted > 0) {
            log.info("Outbox cleanup: deleted {} sent event(s)", deleted);
        }
    }
}

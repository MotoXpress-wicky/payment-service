package com.ecommerce.paymentservice.listener;

import com.ecommerce.paymentservice.config.filter.RequestIdFilter;
import com.ecommerce.paymentservice.service.ReferenceEventService;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** Reads the queue set in "events.queue". Every message changes at most one payment. */
@Component
public class ReferenceEventListener {

    private final ReferenceEventService service;

    public ReferenceEventListener(ReferenceEventService service) {
        this.service = service;
    }

    @RabbitListener(queues = "${events.queue}")
    public void onEvent(Map<String, Object> body,
                        @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String eventName,
                        @Header(name = "messageId", required = false) String messageId,
                        @Header(name = "traceId", required = false) String traceId) {

        if (traceId != null) {
            MDC.put(RequestIdFilter.MDC_KEY, traceId);
        }
        try {
            // The event body is never logged: it holds customer details.
            service.handle(parse(eventName, messageId), eventName, body);
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
    }

    private UUID parse(String eventName, String messageId) {
        try {
            return UUID.fromString(String.valueOf(messageId));
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException("Event '" + eventName + "' has no valid messageId header");
        }
    }
}

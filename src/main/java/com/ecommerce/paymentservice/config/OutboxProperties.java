package com.ecommerce.paymentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings of the event outbox (events.outbox.* in application.yaml).
 *
 * @param enabled       false turns off the background retry (the sender after a commit still works). Used in tests.
 * @param retryInterval how long to wait between two retries while events are waiting
 * @param failurePause  after RabbitMQ refused a message, the sending right after a commit pauses for this long, so
 *                      requests do not queue up behind a broker that is down. The retry job keeps trying. 0 = no pause.
 * @param async         true (normal): a background thread sends, the request only signals it. false: the sending runs
 *                      inside the request thread (used by the tests, so they can check the result at once).
 * @param batchSize     how many waiting events one sending round takes
 * @param keep          sent events are deleted after this time
 */
@ConfigurationProperties(prefix = "events.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10s") Duration retryInterval,
        @DefaultValue("10s") Duration failurePause,
        @DefaultValue("true") boolean async,
        @DefaultValue("50") int batchSize,
        @DefaultValue("7d") Duration keep) {
}

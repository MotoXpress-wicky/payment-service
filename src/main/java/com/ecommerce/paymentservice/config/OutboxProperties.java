package com.ecommerce.paymentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings of the event outbox (events.outbox.* in application.yaml).
 *
 * @param enabled       false turns off the background retry (the sender after a commit still works). Used in tests.
 * @param retryInterval how long to wait between two retries while events are waiting
 * @param batchSize     how many waiting events one sending round takes
 * @param keep          sent events are deleted after this time
 */
@ConfigurationProperties(prefix = "events.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10s") Duration retryInterval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("7d") Duration keep) {
}

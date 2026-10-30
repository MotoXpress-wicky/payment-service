package com.ecommerce.paymentservice.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Switches on the background jobs of the outbox (retry and cleanup). events.outbox.enabled=false turns them off;
 * the tests do that, so no job runs in the background of a test.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "events.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxSchedulingConfig {
}

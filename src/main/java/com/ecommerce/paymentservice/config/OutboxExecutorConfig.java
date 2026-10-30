package com.ecommerce.paymentservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * The thread that sends outbox events after a commit.
 * <p>
 * It is ONE background thread, on purpose. If the request threads did the sending themselves, they would wait for each
 * other (one sender at a time) while still holding their database connections, and the sender needs one more
 * connection for its own transaction. With about as many requests as connections in the pool, nothing could move
 * until the pool timed out. Now a request only signals this thread and returns at once. It also never waits for a
 * RabbitMQ that is down.
 * <p>
 * events.outbox.async=false runs the sending inside the calling thread instead (the tests use this).
 */
@Configuration
public class OutboxExecutorConfig {

    @Bean(name = "outboxExecutor")
    public Executor outboxExecutor(OutboxProperties properties) {
        if (!properties.async()) {
            return new SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("outbox-");
        // On shutdown, give a waiting send a few seconds. Whatever is left stays in the table and is sent after the restart.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}

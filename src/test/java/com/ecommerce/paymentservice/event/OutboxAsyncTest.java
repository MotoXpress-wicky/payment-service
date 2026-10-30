package com.ecommerce.paymentservice.event;

import com.ecommerce.paymentservice.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The normal setting: one background thread sends, and a request only signals it.
 * This is what keeps a burst of requests from stalling (they used to wait for each other while holding database
 * connections), and what keeps a RabbitMQ that is down or slow away from the request.
 */
@SpringBootTest
@TestPropertySource(properties = "events.outbox.async=true")
class OutboxAsyncTest {

    @Autowired
    EventPublisher publisher;
    @Autowired
    OutboxRelay relay;
    @Autowired
    OutboxEventRepository outbox;
    @Autowired
    TransactionTemplate tx;

    @MockitoBean
    RabbitTemplate rabbit;

    @BeforeEach
    void start() {
        outbox.deleteAll();
        reset(rabbit);
    }

    private void send(String name) {
        tx.executeWithoutResult(status -> publisher.publish(name, Map.of("n", name)));
    }

    private void waitUntilNothingWaits() throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (outbox.countBySentAtIsNull() > 0 && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertThat(outbox.countBySentAtIsNull()).isZero();
    }

    @Test
    @DisplayName("a request does not wait for a RabbitMQ that is blocked, and events signalled meanwhile are not forgotten")
    void requestsDoNotWaitAndNothingIsForgotten() throws Exception {
        CountDownLatch inFirstSend = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            inFirstSend.countDown();
            release.await(10, TimeUnit.SECONDS);   // RabbitMQ "hangs" until the test lets it go
            return null;
        }).when(rabbit).convertAndSend(any(String.class), any(String.class), any(Object.class), any(MessagePostProcessor.class));

        long start = System.nanoTime();
        send("a.first");
        assertThat(inFirstSend.await(5, TimeUnit.SECONDS)).as("the sending thread started").isTrue();
        send("b.second");     // these three are saved and signalled while the first send is still blocked
        send("c.third");
        send("d.fourth");
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(millis).as("four requests while RabbitMQ hangs").isLessThan(2000);
        assertThat(outbox.count()).isEqualTo(4);

        release.countDown();
        verify(rabbit, timeout(5000).times(4)).convertAndSend(any(String.class), any(String.class), any(Object.class), any(MessagePostProcessor.class));
        waitUntilNothingWaits();
    }

    @Test
    @DisplayName("a RabbitMQ that is down and slow to answer does not slow the requests")
    void aSlowFailureDoesNotSlowTheRequests() throws Exception {
        doAnswer(call -> {
            Thread.sleep(400);
            throw new AmqpConnectException(new RuntimeException("connection refused"));
        }).when(rabbit).convertAndSend(any(String.class), any(String.class), any(Object.class), any(MessagePostProcessor.class));

        long start = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            send("event." + i);
        }
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(millis).as("five requests, each failing send takes 400 ms").isLessThan(1500);
        assertThat(outbox.count()).isEqualTo(5);
        Thread.sleep(600);   // let the worker finish its failed round
        assertThat(outbox.countBySentAtIsNull()).isEqualTo(5);   // nothing was lost: all wait for the retry job
        verify(rabbit, timeout(2000).atLeastOnce()).convertAndSend(any(String.class), any(String.class), any(Object.class), any(MessagePostProcessor.class));
    }
}

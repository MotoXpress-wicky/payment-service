package com.ecommerce.paymentservice.event;

import com.ecommerce.paymentservice.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The pause after a failure. With a long pause, only the first request after a refusal tries RabbitMQ. The others
 * do not wait for their own failed attempt (they would queue up behind each other); the retry job sends the waiting
 * events later, oldest first. The other tests use a pause of 0, so they see the plain behaviour.
 */
@SpringBootTest
@TestPropertySource(properties = "events.outbox.failure-pause=1h")
class OutboxPauseTest {

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
        brokerUp();
        relay.flush();   // a success clears any pause left by an earlier test
    }

    private void send(String name) {
        tx.executeWithoutResult(status -> publisher.publish(name, Map.of("n", name)));
    }

    private void brokerDown() {
        reset(rabbit);
        doThrow(new AmqpConnectException(new RuntimeException("connection refused")))
                .when(rabbit).convertAndSend(any(String.class), any(String.class), any(Object.class), any(MessagePostProcessor.class));
    }

    private void brokerUp() {
        reset(rabbit);
        doNothing().when(rabbit).convertAndSend(any(String.class), any(String.class), any(Object.class), any(MessagePostProcessor.class));
    }

    @Test
    @DisplayName("after a refusal the commit path pauses; the retry job then sends everything, oldest first")
    void pausesAfterARefusal() throws Exception {
        brokerDown();
        send("a.first");
        Thread.sleep(5);
        send("b.second");
        Thread.sleep(5);
        send("c.third");

        // only the first request tried RabbitMQ. The other two did not wait for a failed attempt.
        verify(rabbit, times(1)).convertAndSend(any(String.class), any(String.class), any(Object.class), any(MessagePostProcessor.class));
        assertThat(outbox.countBySentAtIsNull()).isEqualTo(3);

        brokerUp();
        assertThat(relay.flush()).isEqualTo(3);   // what the retry job does

        InOrder sequence = inOrder(rabbit);
        sequence.verify(rabbit).convertAndSend(eq("app.events"), eq("a.first"), any(Object.class), any(MessagePostProcessor.class));
        sequence.verify(rabbit).convertAndSend(eq("app.events"), eq("b.second"), any(Object.class), any(MessagePostProcessor.class));
        sequence.verify(rabbit).convertAndSend(eq("app.events"), eq("c.third"), any(Object.class), any(MessagePostProcessor.class));
    }

    @Test
    @DisplayName("one success ends the pause: the next commit sends at once again")
    void aSuccessEndsThePause() {
        brokerDown();
        send("a.first");                          // refused: the pause starts

        brokerUp();
        assertThat(relay.flush()).isEqualTo(1);   // the retry job succeeds: the pause ends

        send("b.second");                         // sent right after its commit, no waiting for the job
        verify(rabbit).convertAndSend(eq("app.events"), eq("b.second"), any(Object.class), any(MessagePostProcessor.class));
        assertThat(outbox.countBySentAtIsNull()).isZero();
    }
}

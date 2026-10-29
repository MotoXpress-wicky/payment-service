package com.ecommerce.paymentservice.listener;

import com.ecommerce.paymentservice.service.ReferenceEventService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SpringBootTest
class ReferenceEventListenerTest {

    @Autowired
    Declarables listenTopology;

    @Test
    void passesTheMessageIdAndEventNameOn() {
        ReferenceEventService service = mock(ReferenceEventService.class);
        UUID id = UUID.randomUUID();
        Map<String, Object> body = Map.of("reference", "MX-1");

        new ReferenceEventListener(service).onEvent(body, "order.delivered", id.toString(), "trace-1");

        verify(service).handle(eq(id), eq("order.delivered"), eq(body));
    }

    @Test
    void aMissingOrBrokenMessageIdIsRejected() {
        ReferenceEventListener listener = new ReferenceEventListener(mock(ReferenceEventService.class));

        assertThatThrownBy(() -> listener.onEvent(Map.of(), "order.placed", null, null)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> listener.onEvent(Map.of(), "order.placed", "nope", null)).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void theQueueIsBoundToTheFiveConfiguredEventsAndHasADeadLetterQueue() {
        assertThat(listenTopology.getDeclarablesByType(Binding.class))
                .extracting(Binding::getRoutingKey)
                .containsExactlyInAnyOrder("order.placed", "order.shipped", "order.delivered", "order.returned", "order.cancelled");
        assertThat(listenTopology.getDeclarablesByType(Binding.class))
                .allMatch(b -> b.getExchange().equals("app.events") && b.getDestination().equals("payment.order-events"));

        var queues = listenTopology.getDeclarablesByType(Queue.class);
        assertThat(queues).extracting(Queue::getName).containsExactlyInAnyOrder("payment.order-events", "payment.order-events.dlq");
        Queue main = queues.stream().filter(q -> q.getName().equals("payment.order-events")).findFirst().orElseThrow();
        assertThat(main.getArguments())
                .containsEntry("x-dead-letter-exchange", "")
                .containsEntry("x-dead-letter-routing-key", "payment.order-events.dlq");
    }
}

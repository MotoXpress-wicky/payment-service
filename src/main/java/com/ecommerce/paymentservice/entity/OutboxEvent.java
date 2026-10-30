package com.ecommerce.paymentservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * An event waiting to be sent to RabbitMQ (the "outbox", M46). It is saved in the same transaction as the change it
 * announces. The id is also the messageId header of the message.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "event_name", nullable = false, length = 100)
    private String eventName;

    /** The body as JSON text. */
    @Column(nullable = false, length = 100000)
    private String body;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Empty while the event waits. */
    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    public OutboxEvent(UUID id, String eventName, String body, String traceId) {
        this.id = id;
        this.eventName = eventName;
        this.body = body;
        this.traceId = traceId;
        this.createdAt = Instant.now();
    }
}

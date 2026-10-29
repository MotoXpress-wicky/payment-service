package com.ecommerce.paymentservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** An event that was already handled. RabbitMQ can deliver the same message twice; the second time is skipped. */
@Entity
@Table(name = "processed_messages")
@Getter
@NoArgsConstructor
public class ProcessedMessage {

    @Id
    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();

    public ProcessedMessage(UUID messageId) {
        this.messageId = messageId;
    }
}

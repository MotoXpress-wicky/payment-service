package com.ecommerce.paymentservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The paper from the courier that lists the parcels it paid for. It is the proof that money arrived (M7). */
@Entity
@Table(name = "courier_receipts")
@Getter
@Setter
@NoArgsConstructor
public class CourierReceipt {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 50)
    private String courier;

    /** The courier's own number, for example R-41. Unique per courier. */
    @Column(name = "receipt_no", nullable = false, length = 50)
    private String receiptNo;

    @Column(name = "bank_reference", length = 100)
    private String bankReference;

    @Column(name = "received_on", nullable = false)
    private LocalDate receivedOn;

    /** The sum of the payments on this receipt. */
    @Column(name = "collected_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal collectedTotal;

    /** collected_total minus amount_received: what the courier kept. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal fees;

    @Column(name = "amount_received", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountReceived;

    /** Key of the PDF or photo in the private bucket courier-receipts. */
    @Column(name = "file_key", nullable = false)
    private String fileKey;

    /** The admin user id. */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}

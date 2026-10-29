package com.ecommerce.paymentservice.dto;

import com.ecommerce.paymentservice.entity.PaymentMethod;
import com.ecommerce.paymentservice.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response shapes of payments and courier receipts. */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record PaymentResponse(
            String paymentNo, String reference, PaymentMethod method, BigDecimal amount, String currency,
            PaymentStatus status, String courier, String trackingNumber, String courierReceiptNo,
            Instant createdAt, Instant collectedAt, Instant paidAt, Instant cancelledAt) {
    }

    /** One line of the receipt list. */
    public record ReceiptSummary(
            UUID id, String courier, String receiptNo, String bankReference, LocalDate receivedOn,
            BigDecimal collectedTotal, BigDecimal fees, BigDecimal amountReceived, long paymentCount,
            Long createdBy, Instant createdAt) {
    }

    /** One receipt with the payments it settled. */
    public record ReceiptDetail(ReceiptSummary receipt, List<PaymentResponse> payments) {
    }

    /** One page of a list. */
    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }
}

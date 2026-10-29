package com.ecommerce.paymentservice.service;

import com.ecommerce.paymentservice.dto.PaymentDtos.PaymentResponse;
import com.ecommerce.paymentservice.dto.PaymentDtos.ReceiptSummary;
import com.ecommerce.paymentservice.entity.CourierReceipt;
import com.ecommerce.paymentservice.entity.Payment;
import org.springframework.stereotype.Component;

/** Turns entities into response objects. Call it inside a transaction: it reads lazy fields. */
@Component
public class PaymentMapper {

    public PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(p.getPaymentNo(), p.getReference(), p.getMethod(), p.getAmount(), p.getCurrency(),
                p.getStatus(), p.getCourier(), p.getTrackingNumber(),
                p.getCourierReceipt() == null ? null : p.getCourierReceipt().getReceiptNo(),
                p.getCreatedAt(), p.getCollectedAt(), p.getPaidAt(), p.getCancelledAt());
    }

    public ReceiptSummary toSummary(CourierReceipt r, long paymentCount) {
        return new ReceiptSummary(r.getId(), r.getCourier(), r.getReceiptNo(), r.getBankReference(), r.getReceivedOn(),
                r.getCollectedTotal(), r.getFees(), r.getAmountReceived(), paymentCount, r.getCreatedBy(), r.getCreatedAt());
    }
}

package com.ecommerce.paymentservice.event;

import com.ecommerce.paymentservice.entity.Payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The names and bodies of the events this service publishes, exactly as in docs/mvp/07-events.md.
 * Money is a string with 2 decimals; times are ISO-8601 in UTC.
 */
public final class PaymentEvents {

    public static final String COLLECTED = "payment.collected";
    public static final String SUCCEEDED = "payment.succeeded";
    public static final String CANCELLED = "payment.cancelled";

    private PaymentEvents() {
    }

    public static Map<String, Object> collected(Payment payment) {
        Map<String, Object> body = common(payment);
        body.put("collectedAt", payment.getCollectedAt().toString());
        return body;
    }

    public static Map<String, Object> succeeded(Payment payment, String courierReceiptNo) {
        Map<String, Object> body = common(payment);
        body.put("courierReceiptNo", courierReceiptNo);
        body.put("paidAt", payment.getPaidAt().toString());
        return body;
    }

    public static Map<String, Object> cancelled(Payment payment) {
        Map<String, Object> body = common(payment);
        body.put("cancelledAt", payment.getCancelledAt().toString());
        return body;
    }

    private static Map<String, Object> common(Payment payment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentId", payment.getPaymentNo());
        body.put("reference", payment.getReference());
        body.put("amount", money(payment.getAmount()));
        body.put("currency", payment.getCurrency());
        return body;
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}

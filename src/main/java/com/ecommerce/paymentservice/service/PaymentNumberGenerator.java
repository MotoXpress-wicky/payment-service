package com.ecommerce.paymentservice.service;

import com.ecommerce.paymentservice.config.PaymentProperties;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/** Makes a readable, unique payment number like PAY-5018. The number comes from a database sequence (never repeats). */
@Component
public class PaymentNumberGenerator {

    private final EntityManager entityManager;
    private final String prefix;

    public PaymentNumberGenerator(EntityManager entityManager, PaymentProperties properties) {
        this.entityManager = entityManager;
        this.prefix = properties.paymentNumberPrefix();
    }

    public String next() {
        Number number = (Number) entityManager.createNativeQuery("select nextval('payment_no_seq')").getSingleResult();
        return prefix + number.longValue();
    }
}

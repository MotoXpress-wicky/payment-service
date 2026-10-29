package com.ecommerce.paymentservice;

import com.ecommerce.paymentservice.entity.CourierReceipt;
import com.ecommerce.paymentservice.entity.Payment;
import com.ecommerce.paymentservice.entity.PaymentMethod;
import com.ecommerce.paymentservice.entity.PaymentStatus;
import com.ecommerce.paymentservice.repository.CourierReceiptRepository;
import com.ecommerce.paymentservice.repository.PaymentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real Flyway file V1__create_tables.sql (on H2 in PostgreSQL mode) and lets Hibernate
 * check that the entities match the tables. A typo in the SQL or in an entity fails here.
 */
@DataJpaTest
class RepositoryTest {

    @Autowired
    PaymentRepository payments;
    @Autowired
    CourierReceiptRepository receipts;
    @Autowired
    EntityManager em;

    static Payment payment(String paymentNo, String reference) {
        Payment payment = new Payment();
        payment.setPaymentNo(paymentNo);
        payment.setReference(reference);
        payment.setMethod(PaymentMethod.COD);
        payment.setAmount(new BigDecimal("9500.00"));
        payment.setCurrency("LKR");
        return payment;
    }

    static CourierReceipt receipt(String courier, String receiptNo) {
        CourierReceipt receipt = new CourierReceipt();
        receipt.setCourier(courier);
        receipt.setReceiptNo(receiptNo);
        receipt.setReceivedOn(LocalDate.of(2026, 10, 20));
        receipt.setCollectedTotal(new BigDecimal("12600.00"));
        receipt.setAmountReceived(new BigDecimal("11950.00"));
        receipt.setFees(new BigDecimal("650.00"));
        receipt.setFileKey("Courier A/R-41-x.pdf");
        receipt.setCreatedBy(1L);
        return receipt;
    }

    @Test
    void savesAPaymentAndReadsItBack() {
        Payment saved = payments.saveAndFlush(payment("PAY-5001", "MX-10001"));
        em.clear();

        Payment loaded = payments.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(loaded.getVersion()).isZero();
        assertThat(loaded.getAmount()).isEqualByComparingTo("9500.00");
        assertThat(payments.findByPaymentNo("PAY-5001")).isPresent();
    }

    @Test
    void oneReferenceHasOnePayment() {
        payments.saveAndFlush(payment("PAY-5001", "MX-10001"));

        assertThatThrownBy(() -> payments.saveAndFlush(payment("PAY-5002", "MX-10001")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSameReceiptCannotBeEnteredTwiceForOneCourier() {
        receipts.saveAndFlush(receipt("Courier A", "R-41"));

        assertThat(receipts.existsByCourierIgnoreCaseAndReceiptNo("courier a", "R-41")).isTrue();
        assertThat(receipts.existsByCourierIgnoreCaseAndReceiptNo("Courier B", "R-41")).isFalse();
        assertThatThrownBy(() -> receipts.saveAndFlush(receipt("Courier A", "R-41")))
                .isInstanceOf(DataIntegrityViolationException.class);
        em.clear(); // the failed duplicate must not stay queued in the session
        receipts.saveAndFlush(receipt("Courier B", "R-41"));
    }

    @Test
    void findsPaymentsByTrackingNumberAndByReceipt() {
        CourierReceipt receipt = receipts.save(receipt("Courier A", "R-41"));
        Payment paid = payment("PAY-5001", "MX-10001");
        paid.setCourier("Courier A");
        paid.setTrackingNumber("WB1");
        paid.setCourierReceipt(receipt);
        payments.save(paid);
        Payment other = payment("PAY-5002", "MX-10002");
        other.setCourier("Courier B");
        other.setTrackingNumber("WB1");
        payments.save(other);
        payments.flush();

        assertThat(payments.findByTrackingNumberIn(List.of("WB1"))).hasSize(2);
        assertThat(payments.findByCourierReceiptIdOrderByPaymentNoAsc(receipt.getId()))
                .extracting(Payment::getPaymentNo).containsExactly("PAY-5001");
    }

    @Test
    void locksAreTakenInPaymentNumberOrder() {
        Payment b = payments.save(payment("PAY-5002", "MX-2"));
        Payment a = payments.save(payment("PAY-5001", "MX-1"));
        payments.flush();

        assertThat(payments.findAllByIdForUpdate(List.of(b.getId(), a.getId())))
                .extracting(Payment::getPaymentNo).containsExactly("PAY-5001", "PAY-5002");
        assertThat(payments.findByReferenceForUpdate("MX-1")).isPresent();
    }

    @Test
    void thePaymentNumberSequenceStartsAt5001() {
        Number first = (Number) em.createNativeQuery("select nextval('payment_no_seq')").getSingleResult();

        assertThat(first.longValue()).isEqualTo(5001);
    }
}

package com.ecommerce.paymentservice.repository;

import com.ecommerce.paymentservice.entity.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByPaymentNo(String paymentNo);

    /** Locks the row until the end of the transaction, so two events cannot change one payment at the same moment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.reference = :reference")
    Optional<Payment> findByReferenceForUpdate(@Param("reference") String reference);

    /** Locks many rows, always in the same order (payment number), so two receipts never wait for each other in a circle. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id in :ids order by p.paymentNo")
    List<Payment> findAllByIdForUpdate(@Param("ids") Collection<UUID> ids);

    /** Tracking numbers are unique only per courier, so several couriers may answer. */
    List<Payment> findByTrackingNumberIn(Collection<String> trackingNumbers);

    List<Payment> findByCourierReceiptIdOrderByPaymentNoAsc(UUID courierReceiptId);
}

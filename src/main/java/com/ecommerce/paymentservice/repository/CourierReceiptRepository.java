package com.ecommerce.paymentservice.repository;

import com.ecommerce.paymentservice.entity.CourierReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface CourierReceiptRepository extends JpaRepository<CourierReceipt, UUID>, JpaSpecificationExecutor<CourierReceipt> {

    boolean existsByCourierIgnoreCaseAndReceiptNo(String courier, String receiptNo);
}

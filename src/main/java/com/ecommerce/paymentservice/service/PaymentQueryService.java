package com.ecommerce.paymentservice.service;

import com.ecommerce.paymentservice.config.PaymentProperties;
import com.ecommerce.paymentservice.dto.PaymentDtos.PageResponse;
import com.ecommerce.paymentservice.dto.PaymentDtos.PaymentResponse;
import com.ecommerce.paymentservice.entity.Payment;
import com.ecommerce.paymentservice.entity.PaymentStatus;
import com.ecommerce.paymentservice.exception.ApiException;
import com.ecommerce.paymentservice.repository.PaymentRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** The admin payment list and one payment. */
@Service
@Transactional(readOnly = true)
public class PaymentQueryService {

    private final PaymentRepository payments;
    private final PaymentMapper mapper;
    private final PaymentProperties settings;

    public PaymentQueryService(PaymentRepository payments, PaymentMapper mapper, PaymentProperties settings) {
        this.payments = payments;
        this.mapper = mapper;
        this.settings = settings;
    }

    /** Filters: status, courier, reference (part of it), tracking number (part of it). Newest first. */
    public PageResponse<PaymentResponse> list(PaymentStatus status, String courier, String reference,
                                              String trackingNumber, Integer page, Integer size) {
        Specification<Payment> filter = (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            if (status != null) {
                all.add(cb.equal(root.get("status"), status));
            }
            if (courier != null && !courier.isBlank()) {
                all.add(cb.equal(cb.lower(root.get("courier")), courier.trim().toLowerCase()));
            }
            if (reference != null && !reference.isBlank()) {
                all.add(cb.like(cb.lower(root.get("reference")), contains(reference), '\\'));
            }
            if (trackingNumber != null && !trackingNumber.isBlank()) {
                all.add(cb.like(cb.lower(root.get("trackingNumber")), contains(trackingNumber), '\\'));
            }
            boolean isCountQuery = Long.class.equals(query.getResultType()) || long.class.equals(query.getResultType());
            if (!isCountQuery) {
                query.orderBy(cb.desc(root.get("createdAt")), cb.desc(root.get("paymentNo")));
            }
            return cb.and(all.toArray(new Predicate[0]));
        };

        var result = payments.findAll(filter, pageable(page, size));
        return new PageResponse<>(result.getContent().stream().map(mapper::toResponse).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    public PaymentResponse get(String paymentNo) {
        return payments.findByPaymentNo(paymentNo).map(mapper::toResponse)
                .orElseThrow(() -> ApiException.notFound("Payment not found."));
    }

    Pageable pageable(Integer page, Integer size) {
        int safePage = page == null || page < 0 ? 0 : page;
        int safeSize = size == null || size < 1 ? settings.defaultPageSize() : Math.min(size, settings.maxPageSize());
        return PageRequest.of(safePage, safeSize);
    }

    /** % and _ are wildcards in LIKE. A person typing them must search for the plain characters. */
    private String contains(String text) {
        String escaped = text.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}

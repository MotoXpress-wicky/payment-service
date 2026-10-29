package com.ecommerce.paymentservice.controller;

import com.ecommerce.paymentservice.dto.PaymentDtos.PageResponse;
import com.ecommerce.paymentservice.dto.PaymentDtos.PaymentResponse;
import com.ecommerce.paymentservice.entity.PaymentStatus;
import com.ecommerce.paymentservice.service.PaymentQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin: the payments. SecurityConfig lets only ROLE_ADMIN reach /api/v1/payment/**. */
@RestController
@RequestMapping("/api/v1/payment/admin/payments")
public class AdminPaymentController {

    private final PaymentQueryService service;

    public AdminPaymentController(PaymentQueryService service) {
        this.service = service;
    }

    /** Filters: status, courier, reference, trackingNumber, page, size. */
    @GetMapping
    public PageResponse<PaymentResponse> list(@RequestParam(required = false) PaymentStatus status,
                                              @RequestParam(required = false) String courier,
                                              @RequestParam(required = false) String reference,
                                              @RequestParam(required = false) String trackingNumber,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        return service.list(status, courier, reference, trackingNumber, page, size);
    }

    @GetMapping("/{paymentNo}")
    public PaymentResponse get(@PathVariable String paymentNo) {
        return service.get(paymentNo);
    }
}

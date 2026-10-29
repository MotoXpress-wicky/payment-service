package com.ecommerce.paymentservice.controller;

import com.ecommerce.paymentservice.dto.CurrentUser;
import com.ecommerce.paymentservice.dto.PaymentDtos.PageResponse;
import com.ecommerce.paymentservice.dto.PaymentDtos.ReceiptDetail;
import com.ecommerce.paymentservice.dto.PaymentDtos.ReceiptSummary;
import com.ecommerce.paymentservice.service.CourierReceiptService;
import com.ecommerce.paymentservice.service.CourierReceiptService.NewReceipt;
import com.ecommerce.paymentservice.service.storage.ObjectStorage.StoredFile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Admin: courier receipts. SecurityConfig lets only ROLE_ADMIN reach /api/v1/payment/**. */
@RestController
@RequestMapping("/api/v1/payment/admin/courier-receipts")
public class CourierReceiptController {

    private final CourierReceiptService service;

    public CourierReceiptController(CourierReceiptService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ReceiptSummary> list(@RequestParam(required = false) String courier,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return service.list(courier, page, size);
    }

    /**
     * New receipt. A multipart form: courier, receiptNo, bankReference (optional), receivedOn (yyyy-mm-dd),
     * amountReceived, trackingNumbers (repeat the field, or separate with commas) and the file (PDF or photo).
     * Answers 201 with the receipt and the payments that are now PAID.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ReceiptDetail create(@AuthenticationPrincipal CurrentUser admin,
                                @RequestParam String courier,
                                @RequestParam String receiptNo,
                                @RequestParam(required = false) String bankReference,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedOn,
                                @RequestParam BigDecimal amountReceived,
                                @RequestParam List<String> trackingNumbers,
                                @RequestPart(name = "file", required = false) MultipartFile file) {
        return service.create(admin.id(),
                new NewReceipt(courier, receiptNo, bankReference, receivedOn, amountReceived, trackingNumbers), file);
    }

    @GetMapping("/{id}")
    public ReceiptDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** The PDF or photo. It is private, so it only comes through this admin URL. */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable UUID id) {
        StoredFile file = service.file(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType() == null ? "application/octet-stream" : file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(file.content());
    }
}

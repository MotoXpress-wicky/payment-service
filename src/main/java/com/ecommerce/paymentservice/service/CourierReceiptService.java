package com.ecommerce.paymentservice.service;

import com.ecommerce.paymentservice.config.PaymentProperties;
import com.ecommerce.paymentservice.dto.PaymentDtos.PageResponse;
import com.ecommerce.paymentservice.dto.PaymentDtos.ReceiptDetail;
import com.ecommerce.paymentservice.dto.PaymentDtos.ReceiptSummary;
import com.ecommerce.paymentservice.entity.CourierReceipt;
import com.ecommerce.paymentservice.entity.Payment;
import com.ecommerce.paymentservice.entity.PaymentStatus;
import com.ecommerce.paymentservice.event.EventPublisher;
import com.ecommerce.paymentservice.event.PaymentEvents;
import com.ecommerce.paymentservice.exception.ApiException;
import com.ecommerce.paymentservice.exception.ErrorCode;
import com.ecommerce.paymentservice.repository.CourierReceiptRepository;
import com.ecommerce.paymentservice.repository.PaymentRepository;
import com.ecommerce.paymentservice.service.storage.ObjectStorage;
import com.ecommerce.paymentservice.service.storage.StorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Courier receipts (docs/mvp/06-dynamic/after-order.md, flow 4). The courier pays the shop for many parcels at once
 * and gives a receipt that lists their tracking numbers. The admin types it in with the PDF or photo as proof.
 *
 * Rules:
 * - every tracking number must exist, belong to THAT courier, and be COLLECTED (money is never accepted for a parcel
 *   that the system thinks is still on the road);
 * - all or nothing: if one number is wrong the answer lists every problem and nothing is saved;
 * - the receipt, the file and the PAID payments are saved together: the receipt and the payments in ONE transaction,
 *   the events only after the commit.
 */
@Slf4j
@Service
public class CourierReceiptService {

    private static final Pattern RECEIPT_NO = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,49}$");

    private final CourierReceiptRepository receipts;
    private final PaymentRepository payments;
    private final ObjectStorage storage;
    private final EventPublisher events;
    private final PaymentMapper mapper;
    private final TransactionTemplate tx;
    private final PaymentProperties settings;
    private final PaymentQueryService query;

    public CourierReceiptService(CourierReceiptRepository receipts, PaymentRepository payments, ObjectStorage storage,
                                 EventPublisher events, PaymentMapper mapper, TransactionTemplate tx,
                                 PaymentProperties settings, PaymentQueryService query) {
        this.receipts = receipts;
        this.payments = payments;
        this.storage = storage;
        this.events = events;
        this.mapper = mapper;
        this.tx = tx;
        this.settings = settings;
        this.query = query;
    }

    /** What the admin typed in. */
    public record NewReceipt(String courier, String receiptNo, String bankReference, LocalDate receivedOn,
                             BigDecimal amountReceived, List<String> trackingNumbers) {
    }

    public ReceiptDetail create(Long adminUserId, NewReceipt input, MultipartFile file) {
        String courier = canonicalCourier(input.courier());
        String receiptNo = cleanReceiptNo(input.receiptNo());
        List<String> trackings = cleanTrackingNumbers(input.trackingNumbers());
        BigDecimal amountReceived = cleanAmount(input.amountReceived());
        if (input.receivedOn() == null) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "receivedOn must be a date (yyyy-mm-dd).");
        }
        StoredProof proof = readProof(file);

        // Cheap early checks, before anything is stored. They are repeated under a lock below.
        tx.executeWithoutResult(status -> {
            ensureNew(courier, receiptNo);
            collectable(courier, trackings);
        });

        UUID receiptId = UUID.randomUUID();
        String fileKey = receiptId + "." + proof.type().extension();
        try {
            storage.put(fileKey, proof.bytes(), proof.type().contentType());
        } catch (StorageException e) {
            log.error("Courier receipt file storage failed", e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.INTERNAL_ERROR,
                    "The file storage is not available. Please try again.", List.of());
        }

        try {
            return tx.execute(status -> save(adminUserId, receiptId, fileKey, courier, receiptNo, input, amountReceived, trackings));
        } catch (DataIntegrityViolationException e) {
            removeQuietly(fileKey);
            throw receiptTaken(courier, receiptNo);
        } catch (RuntimeException e) {
            removeQuietly(fileKey);
            throw e;
        }
    }

    public PageResponse<ReceiptSummary> list(String courier, Integer page, Integer size) {
        Specification<CourierReceipt> filter = (root, q, cb) -> {
            boolean isCountQuery = Long.class.equals(q.getResultType()) || long.class.equals(q.getResultType());
            if (!isCountQuery) {
                q.orderBy(cb.desc(root.get("createdAt")), cb.desc(root.get("id")));
            }
            return courier == null || courier.isBlank() ? cb.conjunction()
                    : cb.equal(cb.lower(root.get("courier")), courier.trim().toLowerCase());
        };
        Pageable pageable = query.pageable(page, size);

        return tx.execute(status -> {
            var result = receipts.findAll(filter, pageable);
            return new PageResponse<>(
                    result.getContent().stream().map(r -> mapper.toSummary(r, payments.countByCourierReceiptId(r.getId()))).toList(),
                    result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
        });
    }

    public ReceiptDetail get(UUID id) {
        return tx.execute(status -> detail(receipts.findById(id).orElseThrow(() -> ApiException.notFound("Courier receipt not found."))));
    }

    /** The PDF or photo of a receipt (admin only). */
    public ObjectStorage.StoredFile file(UUID id) {
        String key = tx.execute(status -> receipts.findById(id).map(CourierReceipt::getFileKey)
                .orElseThrow(() -> ApiException.notFound("Courier receipt not found.")));
        try {
            return storage.get(key).orElseThrow(() -> ApiException.notFound("The receipt file was not found."));
        } catch (StorageException e) {
            log.error("Could not read the file of courier receipt {}", id, e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.INTERNAL_ERROR,
                    "The file storage is not available. Please try again.", List.of());
        }
    }

    // =====================================================================
    // the one transaction
    // =====================================================================

    private ReceiptDetail save(Long adminUserId, UUID receiptId, String fileKey, String courier, String receiptNo,
                               NewReceipt input, BigDecimal amountReceived, List<String> trackings) {
        ensureNew(courier, receiptNo);

        // Lock the payments first (always in the same order), then check them again: another admin may have been faster.
        List<Payment> chosen = collectable(courier, trackings);
        List<Payment> locked = payments.findAllByIdForUpdate(chosen.stream().map(Payment::getId).toList());
        locked.forEach(payment -> {
            if (payment.getStatus() != PaymentStatus.COLLECTED) {
                throw ApiException.conflict(ErrorCode.PAYMENT_NOT_COLLECTED,
                        payment.getTrackingNumber() + " changed while the receipt was being saved. Please try again.");
            }
        });

        Set<String> currencies = new LinkedHashSet<>();
        locked.forEach(payment -> currencies.add(payment.getCurrency()));
        if (currencies.size() != 1) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "All payments on one receipt must use the same currency.");
        }

        BigDecimal collectedTotal = locked.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (amountReceived.compareTo(collectedTotal) > 0) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
                    "amountReceived (" + amountReceived + ") is more than the collected total (" + collectedTotal + ").");
        }

        CourierReceipt receipt = new CourierReceipt();
        receipt.setId(receiptId);
        receipt.setCourier(courier);
        receipt.setReceiptNo(receiptNo);
        receipt.setBankReference(blankToNull(input.bankReference()));
        receipt.setReceivedOn(input.receivedOn());
        receipt.setCollectedTotal(collectedTotal);
        receipt.setAmountReceived(amountReceived);
        receipt.setFees(collectedTotal.subtract(amountReceived));
        receipt.setFileKey(fileKey);
        receipt.setCreatedBy(adminUserId);
        receipts.saveAndFlush(receipt);

        Instant now = Instant.now();
        for (Payment payment : locked) {
            payment.setStatus(PaymentStatus.PAID);
            payment.setPaidAt(now);
            payment.setCourierReceipt(receipt);
        }
        payments.flush();

        // One event per payment, sent only after the commit.
        locked.forEach(payment -> events.publish(PaymentEvents.SUCCEEDED, PaymentEvents.succeeded(payment, receiptNo)));
        log.info("Courier receipt {} of {} saved: {} payment(s) are PAID", receiptNo, courier, locked.size());
        return detail(receipt);
    }

    private ReceiptDetail detail(CourierReceipt receipt) {
        List<Payment> settled = payments.findByCourierReceiptIdOrderByPaymentNoAsc(receipt.getId());
        return new ReceiptDetail(mapper.toSummary(receipt, settled.size()), settled.stream().map(mapper::toResponse).toList());
    }

    // =====================================================================
    // checks
    // =====================================================================

    private void ensureNew(String courier, String receiptNo) {
        if (receipts.existsByCourierIgnoreCaseAndReceiptNo(courier, receiptNo)) {
            throw receiptTaken(courier, receiptNo);
        }
    }

    /** One problem per wrong tracking number. The code of the first problem is used; the details list all of them. */
    private List<Payment> collectable(String courier, List<String> trackings) {
        List<Payment> found = payments.findByTrackingNumberIn(trackings);
        List<Payment> usable = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        ErrorCode firstCode = null;

        for (String tracking : trackings) {
            List<Payment> withNumber = found.stream().filter(p -> tracking.equals(p.getTrackingNumber())).toList();
            Optional<Payment> mine = withNumber.stream().filter(p -> courier.equalsIgnoreCase(p.getCourier())).findFirst();

            ErrorCode code = null;
            String message = null;
            if (mine.isEmpty() && withNumber.isEmpty()) {
                code = ErrorCode.TRACKING_NUMBER_UNKNOWN;
                message = tracking + ": there is no payment with this tracking number.";
            } else if (mine.isEmpty()) {
                code = ErrorCode.WRONG_COURIER;
                message = tracking + ": this tracking number belongs to " + withNumber.get(0).getCourier() + ", not " + courier + ".";
            } else if (mine.get().getStatus() != PaymentStatus.COLLECTED) {
                code = ErrorCode.PAYMENT_NOT_COLLECTED;
                message = switch (mine.get().getStatus()) {
                    case PENDING -> tracking + ": mark this parcel delivered first. The payment is still PENDING.";
                    case PAID -> tracking + ": this payment was already paid on another receipt.";
                    default -> tracking + ": this payment is " + mine.get().getStatus() + ".";
                };
            }

            if (code == null) {
                usable.add(mine.get());
            } else {
                problems.add(message);
                if (firstCode == null) {
                    firstCode = code;
                }
            }
        }

        if (!problems.isEmpty()) {
            throw ApiException.conflict(firstCode, "The receipt was not saved, because some tracking numbers cannot be used.", problems);
        }
        return usable;
    }

    private String canonicalCourier(String input) {
        return settings.couriers().stream().filter(name -> input != null && name.equalsIgnoreCase(input.trim())).findFirst()
                .orElseThrow(() -> ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
                        "courier must be one of: " + String.join(", ", settings.couriers()) + "."));
    }

    private String cleanReceiptNo(String input) {
        String receiptNo = input == null ? "" : input.trim();
        if (!RECEIPT_NO.matcher(receiptNo).matches()) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
                    "receiptNo may have letters, digits and . _ / - only (at most 50 characters).");
        }
        return receiptNo;
    }

    private List<String> cleanTrackingNumbers(List<String> input) {
        Set<String> clean = new LinkedHashSet<>();
        if (input != null) {
            input.stream().filter(t -> t != null && !t.isBlank()).map(String::trim).forEach(clean::add);
        }
        if (clean.isEmpty()) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "trackingNumbers must list at least one tracking number.");
        }
        if (clean.size() > settings.maxTrackingNumbersPerReceipt()) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED,
                    "A receipt can list at most " + settings.maxTrackingNumbersPerReceipt() + " tracking numbers.");
        }
        return List.copyOf(clean);
    }

    private BigDecimal cleanAmount(BigDecimal amount) {
        if (amount == null || amount.signum() < 0 || amount.scale() > 2) {
            throw ApiException.badRequest(ErrorCode.VALIDATION_FAILED, "amountReceived must be 0 or more, with at most 2 decimals.");
        }
        return amount.setScale(2);
    }

    private record StoredProof(byte[] bytes, ReceiptFileType type) {
    }

    /** The proof is required: no receipt without its PDF or photo. */
    private StoredProof readProof(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest(ErrorCode.INVALID_FILE, "The receipt file (PDF or photo) is required.");
        }
        long max = settings.receiptFile().maxSizeBytes();
        if (file.getSize() > max) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, ErrorCode.INVALID_FILE,
                    "The file is too big. The limit is " + max / (1024 * 1024) + " MB.", List.of());
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest(ErrorCode.INVALID_FILE, "The file could not be read.");
        }
        ReceiptFileType type = ReceiptFileType.detect(bytes).orElseThrow(() ->
                ApiException.badRequest(ErrorCode.INVALID_FILE, "Only JPEG, PNG, WebP or PDF files are accepted."));
        return new StoredProof(bytes, type);
    }

    private ApiException receiptTaken(String courier, String receiptNo) {
        return ApiException.conflict(ErrorCode.RECEIPT_ALREADY_ENTERED,
                "Receipt " + receiptNo + " of " + courier + " was already entered.");
    }

    private String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    /** A file that cannot be removed is only wasted space. It must not fail the request. */
    private void removeQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not remove stored file '{}'. It stays as an unused file.", key);
        }
    }
}

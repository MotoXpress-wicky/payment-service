package com.ecommerce.paymentservice.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/** The business settings under "payments:" in application.yaml. Wrong values stop the service at startup. */
@Validated
@ConfigurationProperties(prefix = "payments")
public record PaymentProperties(
        @NotBlank String paymentNumberPrefix,
        @NotEmpty List<@NotBlank String> couriers,
        @Min(1) int defaultPageSize,
        @Min(1) int maxPageSize,
        @NotNull @Valid ReceiptFile receiptFile,
        @Min(1) int maxTrackingNumbersPerReceipt
) {

    public record ReceiptFile(@Min(1) long maxSizeBytes) {
    }
}

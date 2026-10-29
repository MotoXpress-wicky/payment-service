package com.ecommerce.paymentservice.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Everything under "storage:" in application.yaml: where the pickup receipt photos live (a private bucket). */
@Validated
@ConfigurationProperties(prefix = "storage")
public record StorageProperties(@NotNull @Valid S3 s3) {

    public record S3(@NotBlank String endpoint, @NotBlank String region, @NotBlank String accessKey,
                     @NotBlank String secretKey, @NotBlank String bucket) {
    }
}

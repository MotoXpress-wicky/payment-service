package com.ecommerce.paymentservice.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * The RabbitMQ names under "events:" in application.yaml.
 * "listen" says which events start which action. The names are only here, never in Java (M20 idea).
 */
@Validated
@ConfigurationProperties(prefix = "events")
public record EventsProperties(
        @NotBlank String exchange,
        @NotBlank String queue,
        @NotEmpty List<@Valid @NotNull Listen> listen
) {

    /** What a payment does when it hears this event. */
    public enum Action {
        /** A new payment starts as PENDING. */
        CREATE,
        /** Save the courier and tracking number on the payment. */
        SET_SHIPMENT,
        /** The courier holds the cash: PENDING to COLLECTED. */
        COLLECT,
        /** No money will come: PENDING to CANCELLED. */
        CANCEL
    }

    public record Listen(@NotBlank String name, @NotNull Action action) {
    }
}

package com.ecommerce.paymentservice.exception;

import lombok.Getter;

/** The error codes this service sends. Common names match user-service, so the UI handles one set. */
@Getter
public enum ErrorCode {

    // ---------- token problems (HTTP 401) ----------
    ACCESS_TOKEN_MISSING("No access token was provided"),
    ACCESS_TOKEN_EXPIRED("Access token has expired", true),
    ACCESS_TOKEN_INVALID("Access token is malformed or has an invalid signature"),
    UNAUTHENTICATED("Authentication is required to access this resource"),

    // ---------- permission (HTTP 403) ----------
    ACCESS_DENIED("You do not have permission to access this resource."),

    // ---------- bad input (HTTP 400) ----------
    VALIDATION_FAILED("Please check the submitted data."),
    INVALID_REQUEST("The request could not be understood."),
    INVALID_FILE("This file cannot be used."),

    // ---------- conflicts (HTTP 409) ----------
    RECEIPT_ALREADY_ENTERED("This courier receipt was already entered."),
    TRACKING_NUMBER_UNKNOWN("There is no payment with this tracking number."),
    WRONG_COURIER("This tracking number belongs to another courier."),
    PAYMENT_NOT_COLLECTED("This payment is not in the status COLLECTED."),
    DATA_CONFLICT("This request conflicts with existing data."),

    // ---------- other ----------
    NOT_FOUND("The requested item was not found."),
    INTERNAL_ERROR("Something went wrong. Please try again later.");

    /** Name of the request attribute the JWT filter uses to pass a code to the entry point. */
    public static final String REQUEST_ATTRIBUTE = "auth.error.code";

    private final String message;
    private final boolean refreshable;

    ErrorCode(String message) {
        this(message, false);
    }

    ErrorCode(String message, boolean refreshable) {
        this.message = message;
        this.refreshable = refreshable;
    }
}

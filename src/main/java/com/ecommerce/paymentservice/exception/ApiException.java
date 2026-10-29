package com.ecommerce.paymentservice.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * A business rule was broken. The service throws it; GlobalExceptionHandler turns it into the
 * shared error JSON. The status and the code are chosen where the rule is checked.
 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;
    private final List<String> details;

    public ApiException(HttpStatus status, ErrorCode code, String message, List<String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public static ApiException conflict(ErrorCode code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message, List.of());
    }

    public static ApiException conflict(ErrorCode code, String message, List<String> details) {
        return new ApiException(HttpStatus.CONFLICT, code, message, details);
    }

    public static ApiException badRequest(ErrorCode code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, List.of());
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, message, List.of());
    }
}

package com.ecommerce.paymentservice.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Turns every exception into the same ErrorResponse body.
 * RestControllerAdvice = one class that catches exceptions from all controllers.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.getStatus())
                .body(ErrorResponse.of(exception.getStatus(), exception.getCode(), exception.getMessage(),
                        request.getRequestURI(), exception.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception,
                                                          HttpServletRequest request) {
        List<String> details = exception.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .toList();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                        ErrorCode.VALIDATION_FAILED.getMessage(), request.getRequestURI(), details));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> handleUnreadableRequest(Exception exception, HttpServletRequest request) {
        log.warn("Unreadable request on {}", request.getRequestURI());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, request.getRequestURI()));
    }

    @ExceptionHandler({MaxUploadSizeExceededException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ErrorResponse> handleBadUpload(Exception exception, HttpServletRequest request) {
        HttpStatus status = exception instanceof MaxUploadSizeExceededException
                ? HttpStatus.CONTENT_TOO_LARGE : HttpStatus.BAD_REQUEST;

        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status, ErrorCode.INVALID_FILE, request.getRequestURI()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException exception,
                                                          HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, request.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException exception,
                                                            HttpServletRequest request) {
        log.warn("Access denied on {}", request.getRequestURI());

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, request.getRequestURI()));
    }

    /**
     * A database rule stopped the save (for example two admins entered the same receipt at the same moment).
     * The services check the rules first, so this is only the safety net.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException exception,
                                                             HttpServletRequest request) {
        log.warn("Database constraint violation on {}", request.getRequestURI());

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(HttpStatus.CONFLICT, ErrorCode.DATA_CONFLICT, request.getRequestURI()));
    }

    /**
     * Last resort. Never send exception.getMessage() to the browser: it can hold
     * SQL, file paths or class names. The real cause goes to the log only.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception exception, HttpServletRequest request) {
        log.error("Unexpected error on {}", request.getRequestURI(), exception);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                        request.getRequestURI()));
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }
}

package dev.lokesh.shop.order.error;

import org.springframework.http.HttpStatus;

/**
 * Machine-readable error codes returned in the ProblemDetail "code" property, for failures that
 * happen before an order exists. Once an order exists, checkout answers with the order itself.
 * Deliberately per service (D10): no shared error module, so services deploy independently.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key header required"),
    DUPLICATE_ITEMS(HttpStatus.BAD_REQUEST, "Duplicate items"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency-Key reused"),
    USER_NOT_FOUND(HttpStatus.UNPROCESSABLE_CONTENT, "User not found"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable"),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "Version conflict");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}

package dev.lokesh.shop.product.error;

import org.springframework.http.HttpStatus;

/**
 * Machine-readable error codes returned in the ProblemDetail "code" property.
 * Deliberately per service (D10): no shared error module, so services deploy independently.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "Product not found"),
    CATEGORY_NOT_FOUND(HttpStatus.UNPROCESSABLE_CONTENT, "Category not found"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "Version conflict"),
    INVALID_ITEMS(HttpStatus.BAD_REQUEST, "Invalid items"),
    UNKNOWN_PRODUCT(HttpStatus.UNPROCESSABLE_CONTENT, "Unknown product"),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "Insufficient stock"),
    RESERVATION_MISMATCH(HttpStatus.CONFLICT, "Reservation mismatch"),
    RESERVATION_RELEASED(HttpStatus.CONFLICT, "Reservation released"),
    RESERVATION_COMMITTED(HttpStatus.CONFLICT, "Reservation committed"),
    RESERVATION_NOT_ACTIVE(HttpStatus.CONFLICT, "Reservation not active");

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

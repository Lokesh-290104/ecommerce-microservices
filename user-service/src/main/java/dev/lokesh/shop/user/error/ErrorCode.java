package dev.lokesh.shop.user.error;

import org.springframework.http.HttpStatus;

/**
 * Machine-readable error codes returned in the ProblemDetail "code" property.
 * Deliberately per service (D10): no shared error module, so services deploy independently.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    PASSWORD_TOO_LONG(HttpStatus.BAD_REQUEST, "Password too long"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "Email already registered");

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

package dev.lokesh.shop.user.error;

/** A business error that maps to one {@link ErrorCode} and HTTP status. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}

package dev.lokesh.shop.order.client;

/** The other service answered with a 4xx: a real, final answer (never retried). */
public class DownstreamRejectedException extends RuntimeException {

    private final String service;
    private final int status;
    private final String code;

    public DownstreamRejectedException(String service, int status, String code, String detail) {
        super(service + " rejected the request (" + status + (code == null ? "" : " " + code) + "): " + detail);
        this.service = service;
        this.status = status;
        this.code = code;
    }

    public String service() {
        return service;
    }

    public int status() {
        return status;
    }

    /** The ProblemDetail "code" (e.g. INSUFFICIENT_STOCK), or null if the body had none. */
    public String code() {
        return code;
    }
}

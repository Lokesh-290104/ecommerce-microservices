package dev.lokesh.shop.order.client;

/**
 * The other service gave no usable answer: connection refused, timeout, 5xx, or its circuit
 * breaker is open. For a write this is ambiguous (it may or may not have happened), which is
 * why every downstream write is idempotent and the reconciler checks what really happened.
 */
public class DownstreamUnavailableException extends RuntimeException {

    private final String service;

    public DownstreamUnavailableException(String service, Throwable cause) {
        super(service + " is unavailable: " + cause.getMessage(), cause);
        this.service = service;
    }

    public String service() {
        return service;
    }
}

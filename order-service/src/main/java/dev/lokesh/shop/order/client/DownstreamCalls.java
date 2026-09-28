package dev.lokesh.shop.order.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.function.Supplier;

/**
 * Runs one downstream HTTP call with its circuit breaker (and optional retry), and turns the
 * outcome into one of three things: a value, {@link DownstreamRejectedException} (4xx, a real
 * answer) or {@link DownstreamUnavailableException} (no usable answer).
 */
@Component
public class DownstreamCalls {

    /**
     * Retry wraps the circuit breaker (Resilience4j's default aspect order), so one checkout
     * during an outage records up to 3 failures on the payment breaker.
     */
    public <T> T call(String service, CircuitBreaker breaker, Retry retry, Supplier<T> request) {
        // Design D18: never hold a DB transaction (connection, row locks) while waiting on the network.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Refusing to call " + service + " inside a database transaction");
        }
        Supplier<T> guarded = CircuitBreaker.decorateSupplier(breaker, request);
        if (retry != null) {
            guarded = Retry.decorateSupplier(retry, guarded);
        }
        try {
            return guarded.get();
        } catch (HttpClientErrorException e) {
            throw rejected(service, e);
        } catch (ResourceAccessException | HttpServerErrorException | CallNotPermittedException e) {
            throw new DownstreamUnavailableException(service, e);
        }
    }

    private static DownstreamRejectedException rejected(String service, HttpClientErrorException e) {
        String code = null;
        String detail = e.getStatusText();
        try {
            ProblemDetail problem = e.getResponseBodyAs(ProblemDetail.class);
            if (problem != null) {
                detail = problem.getDetail() != null ? problem.getDetail() : detail;
                Object c = problem.getProperties() == null ? null : problem.getProperties().get("code");
                code = c == null ? null : c.toString();
            }
        } catch (RuntimeException notProblemJson) {
            // Not a ProblemDetail body: keep the status alone.
        }
        return new DownstreamRejectedException(service, e.getStatusCode().value(), code, detail);
    }
}

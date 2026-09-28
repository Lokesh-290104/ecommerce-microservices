package dev.lokesh.shop.order.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** user-service: does this buyer exist and is the account active? */
@Component
public class UserClient {

    static final String SERVICE = "user-service";

    private final RestClient http;
    private final DownstreamCalls calls;
    private final CircuitBreaker breaker;
    private final Retry retry;

    public UserClient(@Qualifier("userRestClient") RestClient http, DownstreamCalls calls,
                      CircuitBreakerRegistry breakers, RetryRegistry retries) {
        this.http = http;
        this.calls = calls;
        this.breaker = breakers.circuitBreaker("userCB");
        this.retry = retries.retry("userRetry");
    }

    /** False for unknown or deleted users (user-service returns 404 for both). */
    public boolean isActiveUser(long userId) {
        try {
            calls.call(SERVICE, breaker, retry,
                    () -> http.get().uri("/api/users/{id}", userId).retrieve().toBodilessEntity());
            return true;
        } catch (DownstreamRejectedException e) {
            if (e.status() == 404) {
                return false;
            }
            throw e;
        }
    }
}

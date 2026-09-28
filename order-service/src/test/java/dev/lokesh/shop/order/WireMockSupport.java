package dev.lokesh.shop.order;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Real MySQL for orders plus one WireMock server standing in for user-, product- and
 * payment-service (their paths don't overlap). Stubs and circuit breakers are reset before
 * every test, so one test's outage never leaks into the next.
 */
abstract class WireMockSupport extends MySqlTestSupport {

    static final WireMockServer DOWNSTREAM = new WireMockServer(options().dynamicPort());

    static {
        DOWNSTREAM.start();
    }

    @DynamicPropertySource
    static void downstreamUrls(DynamicPropertyRegistry registry) {
        registry.add("shop.services.user-url", DOWNSTREAM::baseUrl);
        registry.add("shop.services.product-url", DOWNSTREAM::baseUrl);
        registry.add("shop.services.payment-url", DOWNSTREAM::baseUrl);
    }

    @Autowired
    CircuitBreakerRegistry breakers;

    @BeforeEach
    void resetDownstream() {
        DOWNSTREAM.resetAll();
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }
}

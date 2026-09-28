package dev.lokesh.shop.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** Injected (not Instant.now()) so time-based logic (token expiry, the reconciler) is testable. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}

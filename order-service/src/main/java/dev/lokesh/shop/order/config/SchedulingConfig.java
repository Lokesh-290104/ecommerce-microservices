package dev.lokesh.shop.order.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Runs the reconciler on its schedule. Tests turn it off and call Reconciler.runOnce directly. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "shop.reconciler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}

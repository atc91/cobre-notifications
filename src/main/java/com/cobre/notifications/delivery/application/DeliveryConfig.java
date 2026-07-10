package com.cobre.notifications.delivery.application;

import com.cobre.notifications.delivery.domain.model.RetryPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Wires the delivery context's domain policies as beans. {@link RetryPolicy} is built from
 * {@link RetryProperties} (A4 defaults) so integration tests can dial the delay to zero via
 * {@code notifications.retry.*} and drive retry sequences without real-time waits.
 */
@Configuration
public class DeliveryConfig {

    @Bean
    RetryPolicy retryPolicy(RetryProperties properties) {
        Duration base = Duration.ofMillis(properties.getBaseDelayMs());
        // Random jitter of [0, base) seconds, matching RetryPolicy.defaults(); guarded so a zero base
        // (tests) does not call ThreadLocalRandom.nextDouble(0), which is illegal.
        DoubleSupplier jitterSeconds = () -> {
            long baseSeconds = base.toSeconds();
            return baseSeconds <= 0 ? 0.0 : ThreadLocalRandom.current().nextDouble(baseSeconds);
        };
        return new RetryPolicy(properties.getMaxAttempts(), base, properties.getFactor(),
                Duration.ofMillis(properties.getCapMs()), jitterSeconds);
    }
}

package com.cobre.notifications.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduling support for the delivery poller (P-07). Lives in {@code common} as
 * framework wiring; it imports no context domain.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}

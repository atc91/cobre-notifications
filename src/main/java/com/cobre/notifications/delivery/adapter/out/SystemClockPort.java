package com.cobre.notifications.delivery.adapter.out;

import com.cobre.notifications.delivery.domain.port.out.ClockPort;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Production binding of {@link ClockPort} to the system clock. Tests substitute a fixed instant so
 * ingest timestamps and backoff timing are deterministic.
 */
@Component
public class SystemClockPort implements ClockPort {

    @Override
    public Instant now() {
        return Instant.now();
    }
}

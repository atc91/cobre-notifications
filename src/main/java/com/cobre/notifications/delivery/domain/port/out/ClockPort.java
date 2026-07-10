package com.cobre.notifications.delivery.domain.port.out;

import java.time.Instant;

/**
 * A trivial outbound port over the current time, injected wherever backoff timing is computed so
 * retry timing is deterministic in tests. Production binds it to the system clock; tests bind a
 * fixed instant.
 */
public interface ClockPort {

    Instant now();
}

package com.cobre.notifications.delivery.domain.model;

/**
 * The delivery lifecycle of a {@link Notification}. Mirrors the {@code notifications.delivery_status}
 * VARCHAR + CHECK set exactly (P-02), so the R2DBC adapter maps the name with no translation.
 *
 * <pre>
 * PENDING ──▶ DELIVERING ──success──▶ DELIVERED   (terminal)
 *                 │
 *                 └──failure──▶ RETRYING ──▶ DELIVERING
 *                                    │
 *                                    └──(exhausted)──▶ FAILED  (terminal, replayable)
 * FAILED ──replay──▶ PENDING
 * </pre>
 */
public enum DeliveryStatus {
    PENDING,
    DELIVERING,
    RETRYING,
    DELIVERED,
    FAILED;

    /** {@code DELIVERED} and {@code FAILED} are the two terminal states. */
    public boolean isTerminal() {
        return this == DELIVERED || this == FAILED;
    }

    /** Only a dead-lettered ({@code FAILED}) notification may be replayed. */
    public boolean isReplayable() {
        return this == FAILED;
    }

    /** True when moving from this status to {@code next} is a legal lifecycle transition. */
    public boolean canTransitionTo(DeliveryStatus next) {
        return switch (this) {
            case PENDING, RETRYING -> next == DELIVERING;
            case DELIVERING -> next == DELIVERED || next == RETRYING || next == FAILED;
            case FAILED -> next == PENDING; // replay
            case DELIVERED -> false;
        };
    }
}

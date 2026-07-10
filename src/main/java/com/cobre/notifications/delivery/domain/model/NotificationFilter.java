package com.cobre.notifications.delivery.domain.model;

import java.time.Instant;

/**
 * Immutable filter criteria for a client-scoped notification listing (P-09). All fields are optional
 * and combine with AND: a {@code null} field is simply not constrained. The {@code clientId} is
 * <em>not</em> part of this value object — scoping is always applied separately by the read port so it
 * can never be accidentally omitted.
 *
 * @param createdFrom inclusive lower bound on {@code createdAt}, or {@code null}
 * @param createdTo   inclusive upper bound on {@code createdAt}, or {@code null}
 * @param status      required {@link DeliveryStatus}, or {@code null} for any status
 */
public record NotificationFilter(Instant createdFrom, Instant createdTo, DeliveryStatus status) {

    public NotificationFilter {
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new IllegalArgumentException("createdFrom must not be after createdTo");
        }
    }

    /** No constraints beyond the client scoping the read port applies. */
    public static NotificationFilter unfiltered() {
        return new NotificationFilter(null, null, null);
    }
}

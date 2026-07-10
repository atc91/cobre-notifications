package com.cobre.notifications.delivery.domain.port.out;

import com.cobre.notifications.delivery.domain.model.DeliveryAttempt;
import com.cobre.notifications.delivery.domain.model.Notification;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Outbound port for persisting the {@code Notification} aggregate and its delivery attempts, and for
 * claiming due work. Implemented by the R2DBC adapter (P-04+). {@link #claimDue} is expected to use
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} so multiple worker instances never double-deliver.
 */
public interface NotificationStorePort {

    /** Insert or update a notification. Ingest relies on id (= {@code event_id}) uniqueness to dedupe. */
    Mono<Notification> save(Notification notification);

    /** Find a notification by id (= platform {@code event_id}); empty if none exists. */
    Mono<Notification> findById(String id);

    /**
     * Atomically claim up to {@code limit} notifications that are due at {@code now} (status
     * {@code PENDING}/{@code RETRYING} and {@code nextRetryAt <= now}), leasing each for delivery:
     * each returned row comes back {@code DELIVERING} with {@code attempts} incremented and
     * {@code claimedAt} set. Implemented with {@code FOR UPDATE SKIP LOCKED} so concurrent or
     * overlapping polls never claim the same row.
     */
    Flux<Notification> claimDue(Instant now, int limit);

    /**
     * Persist the notification's advanced state (status, attempts, {@code nextRetryAt},
     * {@code deliveredAt}, {@code lastError}, {@code claimedAt}) <em>and</em> append its
     * {@code delivery_attempts} audit row in a single transaction — step 3 of the delivery flow,
     * run after each webhook attempt.
     */
    Mono<Void> recordOutcome(Notification notification, DeliveryAttempt attempt);
}

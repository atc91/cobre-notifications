package com.cobre.notifications.delivery.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * The aggregate root of the {@code delivery} context: one platform event on its way to a client
 * webhook. Fields mirror the {@code notifications} table (P-02); {@link #id} is the platform
 * {@code event_id} and therefore the idempotency key.
 *
 * <p>The aggregate is the <em>sole</em> author of status changes. Every lifecycle move is a guarded
 * method that validates the transition via {@link DeliveryStatus#canTransitionTo} and throws
 * {@link IllegalStatusTransitionException} on an illegal move, so an invalid state such as a false
 * {@code DELIVERED} is impossible to reach.
 */
public class Notification {

    private final String id;
    private final String clientId;
    private final String eventType;
    private final String content;
    private String targetUrl;
    private DeliveryStatus deliveryStatus;
    private int attempts;
    private Instant nextRetryAt;
    private Instant claimedAt;
    private String lastError;
    private final Instant createdAt;
    private Instant deliveredAt;
    private Instant updatedAt;

    /**
     * Canonical constructor used by adapters to rehydrate a persisted notification. Application code
     * that ingests a fresh event should prefer {@link #pending}.
     */
    public Notification(String id, String clientId, String eventType, String content,
                        String targetUrl, DeliveryStatus deliveryStatus, int attempts,
                        Instant nextRetryAt, Instant claimedAt, String lastError,
                        Instant createdAt, Instant deliveredAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.content = Objects.requireNonNull(content, "content");
        this.targetUrl = targetUrl;
        this.deliveryStatus = Objects.requireNonNull(deliveryStatus, "deliveryStatus");
        this.attempts = attempts;
        this.nextRetryAt = nextRetryAt;
        this.claimedAt = claimedAt;
        this.lastError = lastError;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.deliveredAt = deliveredAt;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** A brand-new notification straight off ingest: {@code PENDING}, zero attempts, due now. */
    public static Notification pending(String id, String clientId, String eventType,
                                       String content, String targetUrl, Instant now) {
        return new Notification(id, clientId, eventType, content, targetUrl,
                DeliveryStatus.PENDING, 0, null, null, null, now, null, now);
    }

    /** Lease this row for a delivery attempt: {@code PENDING|RETRYING → DELIVERING}. */
    public void claim(Instant now) {
        transitionTo(DeliveryStatus.DELIVERING);
        this.claimedAt = now;
        this.attempts += 1;
        touch(now);
    }

    /** Record a successful (2xx) delivery: {@code DELIVERING → DELIVERED}. */
    public void recordSuccess(Instant now) {
        transitionTo(DeliveryStatus.DELIVERED);
        this.deliveredAt = now;
        this.nextRetryAt = null;
        touch(now);
    }

    /**
     * Record a failed delivery. Schedules a retry ({@code DELIVERING → RETRYING} with
     * {@code nextRetryAt}) while the policy is not exhausted, otherwise dead-letters the row
     * ({@code DELIVERING → FAILED}).
     */
    public void recordFailure(RetryPolicy policy, Instant now, String error) {
        if (policy.isExhausted(attempts)) {
            transitionTo(DeliveryStatus.FAILED);
            this.nextRetryAt = null;
        } else {
            transitionTo(DeliveryStatus.RETRYING);
            this.nextRetryAt = policy.nextRetryAt(attempts, now);
        }
        this.lastError = error;
        touch(now);
    }

    /** Re-enqueue a dead-lettered notification for a fresh delivery cycle: {@code FAILED → PENDING}. */
    public void replay(Instant now) {
        transitionTo(DeliveryStatus.PENDING);
        this.nextRetryAt = now;
        this.claimedAt = null;
        this.lastError = null;
        touch(now);
    }

    private void transitionTo(DeliveryStatus next) {
        if (!deliveryStatus.canTransitionTo(next)) {
            throw new IllegalStatusTransitionException(deliveryStatus, next);
        }
        this.deliveryStatus = next;
    }

    private void touch(Instant now) {
        this.updatedAt = now;
    }

    public String id() {
        return id;
    }

    public String clientId() {
        return clientId;
    }

    public String eventType() {
        return eventType;
    }

    public String content() {
        return content;
    }

    public String targetUrl() {
        return targetUrl;
    }

    public DeliveryStatus deliveryStatus() {
        return deliveryStatus;
    }

    public int attempts() {
        return attempts;
    }

    public Instant nextRetryAt() {
        return nextRetryAt;
    }

    public Instant claimedAt() {
        return claimedAt;
    }

    public String lastError() {
        return lastError;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant deliveredAt() {
        return deliveredAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}

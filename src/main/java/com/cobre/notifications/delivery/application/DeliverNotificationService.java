package com.cobre.notifications.delivery.application;

import com.cobre.notifications.delivery.domain.model.DeliveryAttempt;
import com.cobre.notifications.delivery.domain.model.DeliveryOutcome;
import com.cobre.notifications.delivery.domain.model.Notification;
import com.cobre.notifications.delivery.domain.model.RetryPolicy;
import com.cobre.notifications.delivery.domain.model.WebhookRequest;
import com.cobre.notifications.delivery.domain.port.in.DeliverNotificationUseCase;
import com.cobre.notifications.delivery.domain.port.out.ClockPort;
import com.cobre.notifications.delivery.domain.port.out.NotificationStorePort;
import com.cobre.notifications.delivery.domain.port.out.WebhookClientPort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

/**
 * Use-case implementation for delivering due notifications (P-07/P-08). {@link #deliverDue()} claims
 * the currently-due rows through the store's {@code FOR UPDATE SKIP LOCKED} lease, POSTs each to its
 * webhook, and records the outcome — the claim-and-record half of the inbox pattern.
 *
 * <p>The aggregate is the sole author of its status: on a 2xx the notification records success
 * ({@code DELIVERED}); otherwise it records a failure against the injected {@link RetryPolicy}, which
 * schedules {@code RETRYING} with an exponential-backoff {@code nextRetryAt} or dead-letters to
 * {@code FAILED} once attempts are exhausted. Every attempt writes one append-only
 * {@code delivery_attempts} row, persisted together with the new state in one transaction.
 */
@Service
public class DeliverNotificationService implements DeliverNotificationUseCase {

    private final NotificationStorePort store;
    private final WebhookClientPort webhook;
    private final ClockPort clock;
    private final RetryPolicy retryPolicy;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    public DeliverNotificationService(NotificationStorePort store, WebhookClientPort webhook,
                                      ClockPort clock, RetryPolicy retryPolicy,
                                      ObjectMapper objectMapper, DeliveryProperties properties) {
        this.store = store;
        this.webhook = webhook;
        this.clock = clock;
        this.retryPolicy = retryPolicy;
        this.objectMapper = objectMapper;
        this.batchSize = properties.getBatchSize();
    }

    @Override
    public Mono<Void> deliverDue() {
        // Defer so clock.now() is read at subscription, not assembly: each poll (and each chained run in
        // tests) claims against a fresh "now", which must be >= the nextRetryAt written by a prior run.
        return Flux.defer(() -> store.claimDue(clock.now(), batchSize))
                .flatMap(this::attemptOne)
                .then();
    }

    /** POST one claimed notification, then record the outcome and advance its status. */
    private Mono<Void> attemptOne(Notification notification) {
        WebhookRequest request = new WebhookRequest(
                notification.targetUrl(), payload(notification), notification.id(), null);
        return webhook.post(request).flatMap(outcome -> {
            Instant now = clock.now();
            DeliveryAttempt attempt = new DeliveryAttempt(
                    null, notification.id(), notification.attempts(), now,
                    outcome.httpStatus(), outcome.error(), outcome.durationMs());
            if (outcome.success()) {
                notification.recordSuccess(now);
            } else {
                notification.recordFailure(retryPolicy, now, outcome.error());
            }
            return store.recordOutcome(notification, attempt);
        });
    }

    /** The webhook body: a compact JSON view of the notification. */
    private String payload(Notification notification) {
        return objectMapper.writeValueAsString(Map.of(
                "event_id", notification.id(),
                "event_type", notification.eventType(),
                "content", notification.content()));
    }
}

package com.cobre.notifications.delivery.domain.model;

/**
 * A single webhook delivery request passed to {@code WebhookClientPort}. The {@code idempotencyKey}
 * (= the notification / platform {@code event_id}) is sent as a header so the client can dedupe the
 * duplicates that at-least-once delivery may produce.
 *
 * @param targetUrl      the client webhook URL (validated by the SSRF guard in the adapter, P-06)
 * @param payload        the serialized notification body to POST
 * @param idempotencyKey the notification id, sent as the {@code Idempotency-Key} header
 * @param secret         the per-subscription HMAC secret for payload signing, possibly {@code null}
 */
public record WebhookRequest(
        String targetUrl,
        String payload,
        String idempotencyKey,
        String secret) {
}

# P-06 / P-07 / P-08 · Delivery pipeline — Requirements

## Scope

This slice delivers the **outbound delivery loop**: it takes notifications that ingest has
persisted as `PENDING` (with a `targetUrl` resolved by the P-05 subscription gate) and drives
them to the client's webhook. It provides (P-06) the HTTPS webhook client with an SSRF guard,
strict timeouts, and no redirects; (P-07) a use case that atomically **claims** due rows
(`FOR UPDATE SKIP LOCKED`), POSTs each to its webhook, records a `DeliveryAttempt`, and advances
the notification to `DELIVERED` or `RETRYING`, driven by a polling scheduler; and (P-08) the
retry wiring so a failed attempt schedules an exponential-backoff `nextRetryAt` and, after
`maxAttempts`, dead-letters to the replayable `FAILED` state.

It reuses the P-03 domain unchanged (`Notification` lifecycle methods, `RetryPolicy`,
`DeliveryStatus`, `DeliveryAttempt`, `DeliveryOutcome`, `WebhookRequest`) and the existing port
interfaces; the only port change is turning the P-04 `recordAttempt` stub into an atomic
`recordOutcome`.

This phase **does not**: expose any REST endpoint, including replay (`POST /{id}/replay` is
P-11, which acts on the `FAILED` rows this phase produces); implement the **reaper** that
reclaims rows stuck in `DELIVERING` past their lease (deferred hardening — `claimed_at` is
written but not yet reclaimed); sign payloads with the per-subscription HMAC `secret` (passed as
`null`; post-MVP); add metrics/traces (P-13); introduce Kafka (P-14); or add/alter any Flyway
migration (all columns and tables already exist).

## Functional requirements

1. **Webhook client port.** `WebClientWebhookAdapter` implements `WebhookClientPort.post`,
   POSTing `WebhookRequest.payload` to `targetUrl` over a `WebClient` with connect/read timeouts
   and redirects disabled. It sends `Content-Type: application/json` and an `Idempotency-Key`
   header equal to the notification id, so at-least-once delivery is safe for clients to dedupe.
2. **Never throws on delivery failure.** A non-2xx response, timeout, or transport error is
   returned as `DeliveryOutcome.failed(...)` (a 2xx as `succeeded(...)`), never a thrown
   exception, honoring the port contract. `durationMs` is the measured call duration.
3. **SSRF guard (OWASP A10).** Before any call, the target URL is validated: with the guard
   active (production default) only `https` is allowed and the resolved host must not be
   loopback, link-local (including the `169.254.169.254` cloud-metadata address), private/site-
   local, wildcard, or multicast — every resolved address is checked (DNS-rebinding defence). A
   blocked URL yields a failed outcome with no network call. The guard is toggled by
   `notifications.webhook.block-private-networks` (default `true`).
4. **Atomic due-work claim.** `NotificationStorePort.claimDue(now, limit)` atomically selects up
   to `limit` notifications that are due (`status IN (PENDING, RETRYING)` and `next_retry_at IS
   NULL OR next_retry_at <= now`) using `FOR UPDATE SKIP LOCKED`, sets them to `DELIVERING`,
   stamps `claimed_at`, increments `attempts`, and returns them. Concurrent or overlapping polls
   never claim the same row, so no double-delivery.
5. **Attempt, record, advance.** `DeliverNotificationService.deliverDue()` claims due rows and,
   per row, POSTs to the webhook and then applies the outcome: `recordSuccess` →
   `DELIVERED`/`delivered_at`, or `recordFailure(retryPolicy, …)` → `RETRYING`/`FAILED`. Each
   attempt writes exactly one `delivery_attempts` audit row, and the notification's new state and
   that row are persisted together in one transaction (`recordOutcome`).
6. **Exponential-backoff retry.** A failed attempt below `maxAttempts` sets `deliveryStatus =
   RETRYING` and `nextRetryAt = now + min(baseDelay·factor^attempts + jitter, cap)` (A4 defaults:
   5 attempts, 30s base, ×2, 1h cap), so the row becomes due again and is re-claimed by a later
   poll.
7. **Dead-letter on exhaustion.** When `attempts` reaches `maxAttempts`, the notification becomes
   `FAILED` with `nextRetryAt = null` — the terminal, replayable dead-letter state that P-11's
   replay endpoint will act on.
8. **Scheduler.** `DueDeliveryScheduler` triggers `deliverDue()` on a fixed delay
   (`notifications.delivery.poll-interval-ms`, default 5000), bounded by
   `notifications.delivery.batch-size` (default 100). It is gated by
   `notifications.delivery.scheduler-enabled` (default `true`) so tests can disable auto-run and
   drive `deliverDue()` deterministically.
9. **Retry policy is configurable/injectable.** `RetryPolicy` is a Spring bean
   (`RetryPolicy.defaults()`), so integration tests can substitute a zero-delay policy to
   exercise retry sequences without real-time waits.

## Non-functional requirements

- **Reactive purity.** No `.block()` anywhere; the adapter, use case, and store methods return
  and compose `Mono`/`Flux`; the scheduler subscribes.
- **At-least-once, no false success.** The webhook is called off the persisted row, and
  `DELIVERED` is only recorded on a real 2xx; the worst case is a duplicate (client dedupes via
  `Idempotency-Key`), never a silent loss.
- **Concurrency-safe.** `FOR UPDATE SKIP LOCKED` makes the worker horizontally scalable and
  makes overlapping scheduler runs harmless.
- **Dependency Rule.** SSRF, WebClient, timeouts, and SQL live only in `delivery/adapter/*`;
  `delivery/application` and `delivery/domain` import no adapter/web/R2DBC types; `common` holds
  only framework config and imports no context domain.
- **Security (A03).** All claim/record SQL is parameterized R2DBC.

## Out of scope

- REST endpoints, including `POST /{id}/replay` (P-11) and the read endpoints (P-09/P-10).
- The reaper for `DELIVERING` rows stuck past their `claimed_at` lease (later hardening).
- HMAC payload signing with the per-subscription `secret` (post-MVP; `secret` passed as `null`).
- Observability metrics, latency/attempt histograms, backlog gauge, tracing (P-13).
- Kafka inbound adapter (P-14).
- Any new or altered database schema / Flyway migration.

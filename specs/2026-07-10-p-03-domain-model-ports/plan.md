# P-03 · Domain model + ports — Plan

Tasks grouped by the layers this phase actually touches. P-03 is **pure domain**: it
populates the `domain/model` and `domain/port` packages the P-01 skeleton already carved
out, for the `delivery` and `subscription` contexts only. **No Spring, no R2DBC, no
adapters, no application-layer implementations** — just entities, value objects, port
interfaces, and the JUnit unit tests that pin down status transitions, backoff math, and
subscription matching. Field names and the status set mirror the P-02 schema exactly.

Base package: `com.cobre.notifications`.

## Delivery domain — model & value objects (`delivery/domain/model/`)

- [ ] `DeliveryStatus` — enum `PENDING, DELIVERING, RETRYING, DELIVERED, FAILED`
      (mirrors the `notifications.delivery_status` CHECK set). Predicates: `isTerminal()`
      (`DELIVERED`/`FAILED`), `isReplayable()` (`FAILED` only), and `canTransitionTo(next)`
      encoding the lifecycle (`PENDING→DELIVERING`, `RETRYING→DELIVERING`,
      `DELIVERING→{DELIVERED,RETRYING,FAILED}`, `FAILED→PENDING` via replay).
- [ ] `Notification` — aggregate root with the P-02 columns: `id` (= platform `event_id`),
      `clientId`, `eventType`, `content`, `targetUrl`, `deliveryStatus`, `attempts`,
      `nextRetryAt`, `claimedAt`, `lastError`, `createdAt`, `deliveredAt`, `updatedAt`.
      Behavior methods that own the lifecycle (each guards the source status and throws
      `IllegalStatusTransitionException` on an illegal move, bumping `updatedAt`):
  - `claim(Instant now)` — `PENDING|RETRYING → DELIVERING`, sets `claimedAt = now`,
    `attempts += 1`.
  - `recordSuccess(Instant now)` — `DELIVERING → DELIVERED`, sets `deliveredAt = now`,
    clears `nextRetryAt`.
  - `recordFailure(RetryPolicy policy, Instant now, String error)` — `DELIVERING →`
    `RETRYING` with `nextRetryAt = policy.nextRetryAt(attempts, now)` while
    `!policy.isExhausted(attempts)`, otherwise `→ FAILED` (dead-letter, `nextRetryAt`
    cleared); sets `lastError`.
  - `replay(Instant now)` — `FAILED → PENDING`, `nextRetryAt = now`, clears `claimedAt`
    and `lastError`; throws if not `FAILED`.
- [ ] `DeliveryAttempt` — append-only child record: `id`, `notificationId`, `attemptNo`,
      `attemptedAt`, `httpStatus`, `error`, `durationMs` (mirrors `delivery_attempts`).
- [ ] `RetryPolicy` — value object (A4 defaults: `maxAttempts = 5`, `baseDelay = 30s`,
      `factor = 2`, `cap = 1h`). Constructed with an injected **jitter supplier**
      (`DoubleSupplier`, seconds of jitter) so backoff is deterministic under test.
      `nextRetryAt(int attempts, Instant now) = now + min(baseDelay·factor^attempts +`
      `jitter, cap)`; `isExhausted(int attempts) = attempts >= maxAttempts`. A static
      factory `RetryPolicy.defaults()` wires the production random jitter source.
- [ ] `IllegalStatusTransitionException` — domain `RuntimeException` thrown by illegal
      lifecycle moves (mapped to HTTP later by the `query` context; not mapped here).
- [ ] `PlatformEvent` — inbound value record for ingest: `eventId`, `eventType`, `content`,
      `clientId`, `occurredAt` (shape mirrors `notification_events.json`).
- [ ] `SubscriptionLookup` — delivery-owned result record returned by `SubscriptionPort`:
      `targetUrl`, `secret`. Keeps the `subscription` entity from leaking across the
      context boundary (dependency rule).
- [ ] `WebhookRequest` / `DeliveryOutcome` — request (`targetUrl`, `payload`,
      `idempotencyKey`, `secret`) and result (`httpStatus`, `success`, `error`,
      `durationMs`) records for `WebhookClientPort`.

## Subscription domain — entity & matching rule (`subscription/domain/model/`)

- [ ] `Subscription` — entity with the P-02 `subscriptions` columns: `id`, `clientId`,
      `eventType` (a concrete type or `'*'`), `targetUrl`, `secret`, `active`, `createdAt`.
      Domain rule `matches(String clientId, String eventType)` — true only when `active`,
      `clientId` equals, and `eventType` equals the subscription's type **or** the
      subscription is a wildcard (`'*'`).

## Ports — interfaces only (`delivery/domain/port/{in,out}/`)

- [ ] `port/in/IngestEventUseCase` — `Mono<Void> ingest(PlatformEvent event)` (driven by
      the P-04 seed loader, later the Kafka consumer).
- [ ] `port/in/DeliverNotificationUseCase` — `Mono<Void> deliverDue()` (driven by the P-07
      `DueDeliveryScheduler`).
- [ ] `port/out/NotificationStorePort` — `Mono<Notification> save(Notification n)`,
      `Mono<Notification> findById(String id)`, `Flux<Notification> claimDue(Instant now,`
      `int limit)`, `Mono<Void> recordAttempt(DeliveryAttempt attempt)`.
- [ ] `port/out/SubscriptionPort` — `Mono<SubscriptionLookup> resolve(String clientId,`
      `String eventType)` (empty `Mono` ⇒ not subscribed). Implemented in P-05 by the
      subscription R2DBC adapter, which applies `Subscription.matches(...)`.
- [ ] `port/out/WebhookClientPort` — `Mono<DeliveryOutcome> post(WebhookRequest request)`.
- [ ] `port/out/ClockPort` — `Instant now()`; injected wherever backoff timing is computed
      so retry timing is deterministic in tests.

## Tests — pure JUnit 5, no Spring (`src/test/java/.../delivery|subscription/domain/`)

- [ ] **[Unit]** `DeliveryStatusTest` — `isTerminal`/`isReplayable` predicates and the
      `canTransitionTo` matrix (legal moves allowed, every illegal pair rejected).
- [ ] **[Unit]** `NotificationTest` — lifecycle transitions on the aggregate:
      `claim` (`PENDING→DELIVERING`, `attempts++`, `claimedAt` set) and (`RETRYING→`
      `DELIVERING`); `recordSuccess` (`DELIVERING→DELIVERED`, `deliveredAt` set);
      `recordFailure` below max ⇒ `RETRYING` with `nextRetryAt` + `lastError`;
      `recordFailure` at max ⇒ `FAILED` dead-letter (`nextRetryAt` cleared);
      `replay` (`FAILED→PENDING`, `nextRetryAt = now`); illegal moves (e.g.
      `recordSuccess` on `PENDING`, `replay` on `DELIVERED`) throw
      `IllegalStatusTransitionException`.
- [ ] **[Unit]** `RetryPolicyTest` — backoff math with an injected fixed jitter:
      `nextRetryAt` equals `now + base·2^attempts + jitter` for `attempts = 0,1,2`;
      the `cap` (1h) clamps large attempt counts; `isExhausted` is true exactly at
      `attempts >= maxAttempts`. Determinism proven by the injected `DoubleSupplier`.
- [ ] **[Unit]** `SubscriptionTest` — `matches` is true for an exact `clientId`+`eventType`;
      true for a `'*'` wildcard subscription; false for a different `eventType`; false for a
      different `clientId`; false when `active = false`.

## Smoke test & wrap-up

- [ ] Work through `validations.md` top to bottom; check off each item as observed.
- [ ] `./gradlew test` green — all four unit test classes pass with **no** Testcontainers /
      Docker / Spring context started (these are plain JUnit tests).
- [ ] Confirm the Dependency Rule holds: nothing under `delivery/domain` or
      `subscription/domain` imports Spring, R2DBC, Kafka, `web`, an `adapter` package, or
      another context's `domain` (`SubscriptionLookup`, not `Subscription`, crosses into
      `delivery`).
- [ ] Open the PR; confirm CI is green.

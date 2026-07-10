# P-03 · Domain model + ports — Validations

Each item is independently verifiable. Check only what you have actually observed.

## Delivery domain — model & value objects

- [x] `DeliveryStatus` enum exists with exactly `PENDING, DELIVERING, RETRYING, DELIVERED,
      FAILED` and exposes `isTerminal()`, `isReplayable()`, and `canTransitionTo(next)`.
- [x] `Notification` carries every P-02 column (`id`, `clientId`, `eventType`, `content`,
      `targetUrl`, `deliveryStatus`, `attempts`, `nextRetryAt`, `claimedAt`, `lastError`,
      `createdAt`, `deliveredAt`, `updatedAt`) with `id` typed as `String` (= `event_id`).
- [x] `Notification` exposes `claim`, `recordSuccess`, `recordFailure`, and `replay`; each
      guards the source status and bumps `updatedAt`.
- [x] `DeliveryAttempt` is an append-only record with `id`, `notificationId`, `attemptNo`,
      `attemptedAt`, `httpStatus`, `error`, `durationMs`. _(`id` is `UUID`, matching the
      `delivery_attempts.id UUID` column.)_
- [x] `RetryPolicy` holds the A4 defaults (`maxAttempts=5`, `baseDelay=30s`, `factor=2`,
      `cap=1h`), takes an injected jitter `DoubleSupplier`, and exposes
      `nextRetryAt(attempts, now)` + `isExhausted(attempts)`; `RetryPolicy.defaults()` exists.
- [x] `IllegalStatusTransitionException` (domain `RuntimeException`) exists and is thrown by
      illegal lifecycle moves.
- [x] `PlatformEvent`, `SubscriptionLookup`, `WebhookRequest`, and `DeliveryOutcome` value
      records exist to back the port signatures.

## Subscription domain

- [x] `Subscription` entity mirrors the `subscriptions` columns (`id`, `clientId`,
      `eventType`, `targetUrl`, `secret`, `active`, `createdAt`) and exposes
      `matches(clientId, eventType)`.

## Ports (interfaces only)

- [x] `delivery/domain/port/in/` contains `IngestEventUseCase.ingest(PlatformEvent):Mono<Void>`
      and `DeliverNotificationUseCase.deliverDue():Mono<Void>`.
- [x] `delivery/domain/port/out/` contains `NotificationStorePort` (`save`, `findById`,
      `claimDue`, `recordAttempt`), `SubscriptionPort.resolve(...):Mono<SubscriptionLookup>`,
      `WebhookClientPort.post(...):Mono<DeliveryOutcome>`, and `ClockPort.now():Instant`.
- [x] Every port method returns `Mono`/`Flux`; no blocking types and no `.block()` anywhere
      in the domain.

## Dependency Rule

- [x] No class under `delivery/domain` or `subscription/domain` imports Spring, R2DBC, Kafka,
      `web`, an `adapter` package, or another context's `domain` (grep the import lines —
      verified clean in both directions).
- [x] The `delivery` hexagon references `SubscriptionLookup` (delivery-owned), **not** the
      `subscription.domain.model.Subscription` entity.

## Tests (one checkbox per required test)

- [x] **[Unit]** `DeliveryStatusTest` passes — `isTerminal`/`isReplayable` predicates correct
      and `canTransitionTo` allows every legal move and rejects every illegal one.
- [x] **[Unit]** `NotificationTest` passes — `claim` (from `PENDING` and `RETRYING`),
      `recordSuccess`, `recordFailure` below max (`→RETRYING` + `nextRetryAt`/`lastError`),
      `recordFailure` at max (`→FAILED` dead-letter, `nextRetryAt` cleared), and `replay`
      (`FAILED→PENDING`, `nextRetryAt=now`) behave as specified; illegal moves throw
      `IllegalStatusTransitionException`.
- [x] **[Unit]** `RetryPolicyTest` passes — with an injected fixed jitter, `nextRetryAt`
      equals `now + base·2^attempts + jitter` for `attempts=0,1,2`; the `cap` clamps large
      attempts; `isExhausted` flips exactly at `attempts >= maxAttempts`.
- [x] **[Unit]** `SubscriptionTest` passes — `matches` true for exact `clientId`+`eventType`,
      true for a `'*'` wildcard, false for a different `eventType`, false for a different
      `clientId`, and false when `active=false`.

## CI & merge criteria

- [x] `./gradlew test` succeeds and the four unit test classes run with **no** Spring
      context, Testcontainers, or Docker started (fast, hermetic — 21 tests, build in ~4s
      with the domain-only `--tests` filter).
- [ ] The CI workflow run on the `feature/p-03-domain-model-ports` branch is **green**.
- [ ] **Merge criteria:** all boxes above checked, CI green, no adapter / application /
      repository / schema / wiring code introduced (those belong to P-04+), and the PR
      reviewed and approved.

# P-03 · Domain model + ports — Requirements

## Scope

This phase builds the **domain core** of the service: the pure-Java entities, value
objects, and port interfaces that every later phase depends on, for the `delivery` and
`subscription` bounded contexts. It delivers the `Notification` aggregate and its lifecycle
behavior, the `DeliveryAttempt` audit child, the `DeliveryStatus` enum, the `RetryPolicy`
value object (A4 backoff), the `Subscription` entity with its matching rule, and the
inbound/outbound **port interfaces** those contexts offer and need. All field names and the
status set match the P-02 schema exactly.

It is the "domain hexagon is testable before any infrastructure exists" milestone: the only
code shipped is `domain/model` + `domain/port`, plus **pure JUnit 5 unit tests** (no Spring,
no Testcontainers, no Docker) for status transitions, backoff math, and subscription
matching. It contains **no** port *implementations*, application use-case classes, adapters,
schema, or wiring — those arrive in P-04 onward.

Per the Dependency Rule, `domain` imports nothing but pure Java and Reactor types; no
context's `domain` imports another context's `domain` (the `delivery` hexagon consumes a
delivery-owned `SubscriptionLookup`, never the `subscription` entity).

## Functional requirements

1. `DeliveryStatus` is an enum over `PENDING, DELIVERING, RETRYING, DELIVERED, FAILED`
   (identical to the `notifications.delivery_status` CHECK set). It exposes `isTerminal()`
   (true for `DELIVERED`/`FAILED`), `isReplayable()` (true only for `FAILED`), and
   `canTransitionTo(DeliveryStatus next)` encoding the lifecycle: `PENDING→DELIVERING`,
   `RETRYING→DELIVERING`, `DELIVERING→{DELIVERED, RETRYING, FAILED}`, and `FAILED→PENDING`
   (replay). All other pairs are illegal.
2. `Notification` is the aggregate root and carries every P-02 column: `id` (= the platform
   `event_id`; the idempotency key, a `String`), `clientId`, `eventType`, `content`,
   `targetUrl`, `deliveryStatus`, `attempts`, `nextRetryAt`, `claimedAt`, `lastError`,
   `createdAt`, `deliveredAt`, `updatedAt`.
3. `Notification` owns its lifecycle through guarded behavior methods; each validates the
   current status against `DeliveryStatus.canTransitionTo(...)`, throws
   `IllegalStatusTransitionException` on an illegal move, and advances `updatedAt`:
   - `claim(Instant now)`: `PENDING|RETRYING → DELIVERING`; sets `claimedAt = now` and
     `attempts += 1`.
   - `recordSuccess(Instant now)`: `DELIVERING → DELIVERED`; sets `deliveredAt = now` and
     clears `nextRetryAt`.
   - `recordFailure(RetryPolicy policy, Instant now, String error)`: `DELIVERING →`
     `RETRYING` with `nextRetryAt = policy.nextRetryAt(attempts, now)` while the policy is
     not exhausted, else `→ FAILED` (dead-letter, `nextRetryAt` cleared); sets `lastError`.
   - `replay(Instant now)`: `FAILED → PENDING`; sets `nextRetryAt = now`; clears `claimedAt`
     and `lastError`.
4. `DeliveryAttempt` is an append-only value record mirroring `delivery_attempts`:
   `id`, `notificationId`, `attemptNo`, `attemptedAt`, `httpStatus`, `error`, `durationMs`.
5. `RetryPolicy` is a value object with the A4 defaults (`maxAttempts = 5`,
   `baseDelay = 30s`, `factor = 2`, `cap = 1h`) and an **injected jitter supplier**
   (`DoubleSupplier`, seconds). `nextRetryAt(int attempts, Instant now)` returns
   `now + min(baseDelay·factor^attempts + jitter, cap)`; `isExhausted(int attempts)` is
   `attempts >= maxAttempts`. A `RetryPolicy.defaults()` factory supplies the production
   random jitter source; tests inject a fixed supplier for exact assertions.
6. `Subscription` is the `subscription` context entity mirroring the `subscriptions` table
   (`id`, `clientId`, `eventType` — a concrete type or `'*'` — `targetUrl`, `secret`,
   `active`, `createdAt`). `matches(String clientId, String eventType)` returns true only
   when `active` is true, `clientId` matches, and the requested `eventType` equals the
   subscription's `eventType` **or** the subscription's `eventType` is `'*'`.
7. The `delivery` context defines its **inbound** ports as interfaces:
   `IngestEventUseCase.ingest(PlatformEvent) : Mono<Void>` and
   `DeliverNotificationUseCase.deliverDue() : Mono<Void>`.
8. The `delivery` context defines its **outbound** ports as interfaces:
   - `NotificationStorePort` — `save`, `findById`, `claimDue(Instant, int)`,
     `recordAttempt(DeliveryAttempt)` (returning `Mono`/`Flux`).
   - `SubscriptionPort` — `resolve(String clientId, String eventType) : Mono<SubscriptionLookup>`;
     an empty `Mono` means "not subscribed".
   - `WebhookClientPort` — `post(WebhookRequest) : Mono<DeliveryOutcome>`.
   - `ClockPort` — `now() : Instant`.
9. Supporting value records exist for the port signatures: `PlatformEvent` (ingest input,
   shape aligned with `notification_events.json`), `SubscriptionLookup` (`targetUrl`,
   `secret` — delivery-owned so the subscription entity never crosses the boundary),
   `WebhookRequest`, and `DeliveryOutcome`.
10. Reactive contract holds at the port boundary: every port method returns `Mono`/`Flux`;
    no `.block()`; no blocking types in signatures.

## Non-functional requirements

- **Dependency Rule (architecture-critical):** no `domain` class imports Spring, R2DBC,
  Kafka, `web`, any `adapter` package, or another context's `domain`. Only pure Java +
  Reactor types are permitted. This is what keeps the core unit-testable with zero
  infrastructure and is verified by the tests running without a Spring context.
- **Test hermeticity / speed:** all P-03 tests are plain JUnit 5 and start **no** container,
  Spring context, or Docker; they are deterministic (backoff jitter and clock are injected).
- **Schema fidelity:** domain field names and the status set are 1:1 with the P-02
  migrations, so the P-04+ R2DBC adapters map without translation friction.
- **Data-integrity support (OWASP A08):** the aggregate is the sole author of status
  changes; illegal transitions are impossible without throwing, preventing invalid states
  such as a false `DELIVERED`.

## Out of scope

- The `query` context ports (`QueryNotificationsUseCase`, `ReplayNotificationUseCase`) and
  any query read model — deferred to **P-09/P-10/P-11**, built alongside the endpoints.
- Any **port implementation**: R2DBC repositories, the WebClient webhook adapter, the seed
  loader, the scheduler, the subscription adapter — P-04 onward.
- Application-layer use-case classes and `@Transactional` orchestration — the phase that
  introduces each use case owns its implementation.
- Persistence concerns: `@Table` mapping annotations, R2DBC entities, SQL — the schema
  already exists (P-02); mapping is P-04+.
- Exception→HTTP mapping / `GlobalExceptionHandler` — arrives with the `common` web layer
  and the API phases; P-03 only defines the domain exception type.
- The SSRF URL guard (P-06), real retry/scheduler orchestration (P-07/P-08), auth (P-12),
  and observability (P-13).

# P-06 / P-07 / P-08 · Delivery pipeline — Plan

This slice makes notifications actually **deliver**. It spans three roadmap phases as one
branch because they form one working loop: the webhook client (P-06), the claim-and-deliver
use case + scheduler (P-07), and the retry/back-off/dead-letter wiring (P-08). It builds on
P-05 (notifications are persisted `PENDING` with a resolved `targetUrl`) and P-03 (the whole
domain — `Notification.claim/recordSuccess/recordFailure`, `RetryPolicy`, `DeliveryStatus`,
`DeliveryAttempt`, `DeliveryOutcome`, `WebhookRequest` — plus the `WebhookClientPort` /
`DeliverNotificationUseCase` / `NotificationStorePort` port interfaces).

Base package: `com.cobre.notifications`. All work lands in the `delivery` context plus a small
`common` scheduling switch. **No new Flyway migration** — `notifications` (with `attempts`,
`next_retry_at`, `claimed_at`, `delivered_at`, `last_error`) and `delivery_attempts` already
exist (P-02).

**Key decisions (from spec Q&A):**
- Stub server for HTTP tests: **OkHttp MockWebServer** (enqueue-ordered responses model retry
  sequences deterministically).
- SSRF guard is **strict by default**; a `notifications.webhook.block-private-networks`
  property (default `true`) is set `false` in the test profile so the loopback stub is
  reachable. The pure guard logic is unit-tested regardless.
- The **reaper** (reclaim rows stuck in `DELIVERING` past their lease) is **out of scope** here
  — deferred to later hardening. `claimed_at` is written on claim but not yet reclaimed.
- Webhook body: a small JSON object `{"event_id","event_type","content"}` serialized from the
  notification. HMAC signing stays deferred (the `secret` is passed as `null`).

## Build & dependencies (`build.gradle.kts`)

- [ ] Add `testImplementation("com.squareup.okhttp3:mockwebserver")` (version managed by the
      Spring Boot BOM). No production dependency is added — WebClient/reactor-netty are already
      on the classpath via `spring-boot-starter-webflux`.

## Backend — P-06 · Webhook client adapter (`delivery/adapter/out/`)

- [ ] `WebhookProperties` — `@ConfigurationProperties("notifications.webhook")`:
      `blockPrivateNetworks` (default `true`), `connectTimeoutMs` (default `2000`),
      `readTimeoutMs` (default `5000`).
- [ ] `SsrfGuard` — a pure collaborator (no Spring, no I/O beyond `InetAddress` resolution)
      exposing `boolean isAllowed(String url)`. When `blockPrivateNetworks` is on: require
      scheme **https**; resolve the host and **reject** loopback, link-local (incl. the
      `169.254.169.254` metadata address), site-local/private, wildcard, and multicast
      addresses — re-checking every resolved `InetAddress` (DNS-rebinding defence). When off
      (test profile), permit `http` and loopback so the stub is reachable.
- [ ] `WebClientWebhookAdapter implements WebhookClientPort` — `@Component`. Builds a `WebClient`
      from a reactor-netty `HttpClient` configured with the connect/read timeouts and
      **`followRedirect(false)`**. `post(WebhookRequest)`:
  - run `SsrfGuard.isAllowed(targetUrl)` first; if blocked, return
    `DeliveryOutcome.failed(null, "blocked by SSRF guard", 0)` — **never throw**;
  - otherwise `POST` the `payload` with headers `Content-Type: application/json` and
    `Idempotency-Key: <idempotencyKey>`; measure wall-clock `durationMs`;
  - map the result: 2xx → `DeliveryOutcome.succeeded(status, durationMs)`; non-2xx →
    `failed(status, "HTTP <status>", durationMs)`; timeout/connection error →
    `failed(null, <message>, durationMs)`. The port contract "never throws for a delivery
    failure" holds (`onErrorResume` maps transport errors to a failed outcome).

## Backend — P-07 · Store adapter: claim & record (`delivery/adapter/out/`)

- [ ] Extend `NotificationStorePort`: replace the P-04 stub `recordAttempt(DeliveryAttempt)`
      with `recordOutcome(Notification notification, DeliveryAttempt attempt)` — persist the
      notification's advanced state **and** append the attempt in one transaction. Update the
      port Javadoc.
- [ ] `R2dbcNotificationRepository.claimDue(Instant now, int limit)` — atomic claim via a single
      `UPDATE ... RETURNING`:
      ```sql
      UPDATE notifications SET delivery_status='DELIVERING', claimed_at=:now,
             attempts=attempts+1, updated_at=:now
      WHERE id IN (
        SELECT id FROM notifications
        WHERE delivery_status IN ('PENDING','RETRYING')
          AND (next_retry_at IS NULL OR next_retry_at <= :now)
        ORDER BY next_retry_at NULLS FIRST
        FOR UPDATE SKIP LOCKED
        LIMIT :limit)
      RETURNING *;
      ```
      `SKIP LOCKED` lets multiple workers/overlapping polls claim disjoint rows and never
      double-deliver. Maps `RETURNING` rows through the existing `mapRow` (they come back already
      `DELIVERING`, `attempts` incremented, `claimed_at` set).
- [ ] `R2dbcNotificationRepository.recordOutcome(...)` — within a `TransactionalOperator`
      (from the autoconfigured `ReactiveTransactionManager`): `UPDATE notifications SET
      delivery_status, attempts, next_retry_at, claimed_at, last_error, delivered_at, updated_at
      WHERE id=:id` then `INSERT INTO delivery_attempts (notification_id, attempt_no,
      attempted_at, http_status, error, duration_ms) VALUES (...)` (`id` defaults to `uuidv7()`).

## Backend — P-07 · Delivery use case + scheduler

- [ ] `DeliverNotificationService implements DeliverNotificationUseCase`
      (`delivery/application/`, `@Service`) — depends on `NotificationStorePort`,
      `WebhookClientPort`, `ClockPort`, `RetryPolicy`, and `ObjectMapper`. `deliverDue()`:
  - `store.claimDue(clock.now(), batchSize)` then `flatMap` each claimed notification through
    `attemptOne(...)`, `.then()`. No `.block()`.
  - `attemptOne(Notification n)`: build `WebhookRequest(n.targetUrl(), payload(n), n.id(),
    null)`; `webhook.post(req)`; on the `DeliveryOutcome`, stamp `now = clock.now()`, build the
    `DeliveryAttempt(null, n.id(), n.attempts(), now, outcome.httpStatus(), outcome.error(),
    outcome.durationMs())`, then apply the domain transition — `n.recordSuccess(now)` on
    success, else `n.recordFailure(retryPolicy, now, outcome.error())` — and
    `store.recordOutcome(n, attempt)`.
  - `payload(Notification)`: serialize `{"event_id","event_type","content"}` via the injected
    `ObjectMapper`.
- [ ] `DeliveryProperties` — `@ConfigurationProperties("notifications.delivery")`:
      `batchSize` (default `100`), `pollIntervalMs` (default `5000`).
- [ ] `DueDeliveryScheduler` (`delivery/adapter/in/`, `@Component`) — a
      `@Scheduled(fixedDelayString="${notifications.delivery.poll-interval-ms:5000}")` method
      that invokes `deliverDue().subscribe(...)` (logging errors). Gated by
      `@ConditionalOnProperty("notifications.delivery.scheduler-enabled", matchIfMissing=true)`
      so `@SpringBootTest` contexts don't auto-run it; overlap is harmless because `claimDue`
      uses `SKIP LOCKED`.
- [ ] `DeliveryConfig` (`delivery/application/`, `@Configuration`) — `@Bean RetryPolicy
      retryPolicy(RetryProperties)` built from `notifications.retry.*` (A4 defaults). Property-
      driven (not hardcoded `RetryPolicy.defaults()`) so integration tests dial the delay to zero
      via `@SpringBootTest` properties. Backed by `RetryProperties` (`delivery/application/`):
      `max-attempts` (5), `base-delay-ms` (30000), `factor` (2), `cap-ms` (3600000).
- [ ] `SchedulingConfig` (`common/`, `@Configuration @EnableScheduling`) — enables Spring
      scheduling without importing any context's domain.

## Backend — P-08 · Retry / back-off / dead-letter wiring

- [ ] No new domain code: `Notification.recordFailure(RetryPolicy, now, error)` already computes
      `nextRetryAt = now + min(base·2^attempts + jitter, cap)` and dead-letters to `FAILED` at
      `maxAttempts` (unit-tested in P-03). P-08 is complete once `DeliverNotificationService`
      passes the injected `RetryPolicy` into `recordFailure` and `recordOutcome` persists
      `next_retry_at` / terminal `FAILED`, so a `RETRYING` row is re-claimed by the next poll and
      a `FAILED` row is the replayable dead-letter (P-11 acts on it).

## Configuration & wiring (`src/main/resources/`)

- [ ] `application.yml` — add the `notifications.webhook.*` and `notifications.delivery.*`
      blocks with the defaults above (env-overridable, 12-factor). Register the two
      `@ConfigurationProperties` types (`@ConfigurationPropertiesScan` on the application or
      `@EnableConfigurationProperties`).
- [ ] `src/test/resources/application.yml` — set `notifications.webhook.block-private-networks:
      false` (so the loopback MockWebServer is reachable) and
      `notifications.delivery.scheduler-enabled: false` (ITs drive `deliverDue()` directly).

## Tests

Note: the retry **math** and every **status transition** are already unit-tested in P-03
(`RetryPolicyTest`, `NotificationTest`, `DeliveryStatusTest`) — by the decision rule they are
**not** re-tested here. The tests below cover the new behaviour each layer introduces.

- [ ] **[Unit]** `SsrfGuardTest` — with `blockPrivateNetworks=true`: allow a public `https` URL;
      block `http` scheme; block loopback (`127.0.0.1`, `::1`), private ranges (`10.x`,
      `192.168.x`, `172.16.x`), and the link-local metadata address `169.254.169.254`. With
      `blockPrivateNetworks=false`: permit `http://127.0.0.1`. IP-literal URLs only (offline, no
      DNS).
- [ ] **[Unit]** `DeliverNotificationServiceTest` — mocked `NotificationStorePort`,
      `WebhookClientPort`, `ClockPort` + a fixed `RetryPolicy`. Pins the **orchestration** (not
      the transition math): a success outcome drives `recordSuccess` and a `recordOutcome` with a
      success `DeliveryAttempt` (`attemptNo == attempts`, `httpStatus` set); a failure outcome
      drives `recordFailure(policy, …)` and a `recordOutcome` carrying the error; the webhook is
      called once per claimed row with `idempotencyKey == event_id`.
- [ ] **[Integration]** `WebClientWebhookAdapterIT` — OkHttp MockWebServer, `block-private-
      networks=false`. 2xx → `succeeded` outcome and the recorded request carried `Content-Type:
      application/json` + `Idempotency-Key: <event_id>`; 500 → `failed(500, …)` (no throw); a
      delayed response past `read-timeout-ms` → `failed(null, timeout)`; a 302 → **not followed**,
      surfaced as a non-2xx failure. Plus one case with `block-private-networks=true` pointing at
      `127.0.0.1` → `failed` outcome with **no** network call (guard wired into the adapter).
- [ ] **[Integration]** `R2dbcDeliveryClaimIT` — Testcontainers Postgres + `StepVerifier`.
      `claimDue` moves due `PENDING`/`RETRYING` rows to `DELIVERING` (attempts +1, `claimed_at`
      set) and returns them; rows with a future `next_retry_at` and terminal rows
      (`DELIVERED`/`FAILED`) are **not** claimed. Two concurrent `claimDue` calls partition the
      due rows with **no overlap** (`SKIP LOCKED`). `recordOutcome` atomically updates the
      notification (status/`next_retry_at`/`delivered_at`/attempts) and inserts one
      `delivery_attempts` row.
- [ ] **[Integration]** `DeliveryPipelineIT` — Testcontainers Postgres + MockWebServer with the
      retry policy dialed to zero delay and `max-attempts=3` via `notifications.retry.*`, so
      `next_retry_at <= now` immediately. **Retry → success:** seed a `PENDING` notification
      targeting the stub, enqueue `500, 500, 200`, call `deliverDue()` three times → final
      `DELIVERED`, `attempts=3`, `delivered_at` set, **3** `delivery_attempts` rows. **Retry →
      exhaustion:** enqueue `500, 500, 500`, call `deliverDue()` three times → `FAILED`,
      `attempts=3`, `next_retry_at` null, **3** attempt rows. This is the flaky-stub retry
      journey the P-08 roadmap row names.

## Smoke test & wrap-up

- [ ] Work through `validations.md` top to bottom; check off each item as observed.
- [ ] `./gradlew build` green from a clean checkout (unit tests hermetic; integration tests pass
      against Testcontainers + MockWebServer, Docker running).
- [ ] Local smoke: `docker compose up notifications-db flyway -d`, run the app with the `local`
      profile (both seed loaders + the scheduler on). Observe the scheduler claim due rows and
      write `delivery_attempts`; against the unreachable `*.example.com` seed URLs the rows
      advance `PENDING → DELIVERING → RETRYING` with `next_retry_at` set and `attempts`
      incrementing (point a subscription `target_url` at a local echo server to see `DELIVERED`).
- [ ] Confirm the Dependency Rule still holds: `delivery/application` and `delivery/domain`
      import no `adapter`, web, or R2DBC types; the SSRF/WebClient concerns live only in
      `delivery/adapter/out`; `common` imports no context domain.
- [ ] Open the PR (stacked on the P-05 branch); confirm CI is green.

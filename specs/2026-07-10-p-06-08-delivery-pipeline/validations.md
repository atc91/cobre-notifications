# P-06 / P-07 / P-08 · Delivery pipeline — Validations

Each item is independently verifiable. Check only what you have actually observed.

## P-06 — Webhook client adapter

- [x] `WebhookProperties` binds `notifications.webhook.*` (`block-private-networks` default
      `true`, `connect-timeout-ms`, `read-timeout-ms`).
- [x] `SsrfGuard.isAllowed` allows a public `https` URL and blocks `http` scheme, loopback,
      private/site-local, link-local (incl. `169.254.169.254`), wildcard, and multicast hosts,
      re-checking every resolved address; loopback/`http` is permitted only when
      `block-private-networks` is off.
- [x] `WebClientWebhookAdapter` implements `WebhookClientPort`, uses a `WebClient` with the
      configured timeouts and `followRedirect(false)`, and sends `Content-Type: application/json`
      + `Idempotency-Key: <event_id>`.
- [x] `post(...)` returns `succeeded`/`failed` `DeliveryOutcome`s and **never throws** for a
      non-2xx, timeout, transport error, or SSRF block.

## P-07 — Store adapter, use case & scheduler

- [x] `NotificationStorePort.recordOutcome(Notification, DeliveryAttempt)` replaces the P-04
      `recordAttempt` stub.
- [x] `claimDue(now, limit)` issues the `UPDATE ... FOR UPDATE SKIP LOCKED ... RETURNING`,
      selecting only due `PENDING`/`RETRYING` rows and returning them as `DELIVERING` with
      `attempts` incremented and `claimed_at` set.
- [x] `recordOutcome` updates the notification and inserts one `delivery_attempts` row in a
      single transaction (`TransactionalOperator`).
- [x] `DeliverNotificationService` implements `DeliverNotificationUseCase`, claims due rows,
      POSTs each (`idempotencyKey == event_id`, payload `{"event_id","event_type","content"}`),
      and applies `recordSuccess`/`recordFailure` then `recordOutcome`; no `.block()`. The clock
      is read at subscription (`Flux.defer`) so each poll claims against a fresh `now`.
- [x] `DueDeliveryScheduler` runs `deliverDue()` on `fixedDelay`
      (`notifications.delivery.poll-interval-ms`), gated by
      `notifications.delivery.scheduler-enabled` (default on), bounded by `batch-size`.
- [x] `RetryPolicy` is a `@Bean` built from `RetryProperties` (A4 defaults, configurable);
      `common/SchedulingConfig` enables `@EnableScheduling` without importing context domain.

## P-08 — Retry / back-off / dead-letter

- [x] A failed attempt below `maxAttempts` persists `RETRYING` with `next_retry_at = now +
      min(base·2^attempts + jitter, cap)`, and the row is re-claimed by a later poll.
- [x] The attempt at `maxAttempts` persists `FAILED` with `next_retry_at = null` (replayable
      dead-letter).

## Configuration

- [x] `application.yml` declares the `notifications.webhook.*`, `notifications.delivery.*`, and
      `notifications.retry.*` blocks; the `@ConfigurationProperties` types are registered via
      `@ConfigurationPropertiesScan`.
- [x] `src/test/resources/application.yml` sets `notifications.webhook.block-private-networks:
      false` and `notifications.delivery.scheduler-enabled: false`.
- [x] `build.gradle.kts` adds the `com.squareup.okhttp3:mockwebserver` test dependency (pinned,
      not in the Boot BOM) and no new production dependency.

## Tests (one checkbox per required test)

- [x] **[Unit]** `SsrfGuardTest` passes — public `https` allowed; `http`, loopback, private,
      link-local/metadata, wildcard, multicast blocked; loopback/`http` allowed only when the
      guard is off. (Offline, IP-literal URLs.)
- [x] **[Unit]** `DeliverNotificationServiceTest` passes — success outcome → `recordSuccess` +
      `recordOutcome` with a success `DeliveryAttempt` (`attemptNo == attempts`); failure outcome
      → `recordFailure(policy, …)` + `recordOutcome` with the error; webhook called once per
      claimed row with `idempotencyKey == event_id`. (Retry math / transitions not re-tested —
      covered by P-03 unit tests.)
- [x] **[Integration]** `WebClientWebhookAdapterIT` passes (MockWebServer) — 2xx → `succeeded`
      with the right headers recorded; 500 → `failed(500)`; delayed response past `read-timeout`
      → `failed(null, timeout)`; 302 not followed → non-2xx failure; SSRF-blocked target
      (guard on) → `failed`, no network call.
- [x] **[Integration]** `R2dbcDeliveryClaimIT` passes (Testcontainers Postgres) — due rows
      claimed to `DELIVERING` (+1 attempt, `claimed_at`); future/terminal rows not claimed; two
      concurrent claims partition rows with no overlap (`SKIP LOCKED`); `recordOutcome`
      atomically updates the notification and appends one attempt row.
- [x] **[Integration]** `DeliveryPipelineIT` passes (Testcontainers Postgres + MockWebServer,
      zero-delay policy via `notifications.retry.*`) — retry → success: `500,500,200` over three
      `deliverDue()` runs → `DELIVERED`, `attempts=3`, `delivered_at` set, 3 attempt rows; retry
      → exhaustion (`max-attempts=3`): `500,500,500` over three runs → `FAILED`, `attempts=3`,
      `next_retry_at` null, 3 attempt rows.

## CI & merge criteria

- [x] `./gradlew build` succeeds from a clean checkout: unit tests hermetic; integration tests
      pass against Testcontainers Postgres + MockWebServer (Docker running).
- [ ] Local smoke: with the `local` profile the scheduler claims due rows and writes
      `delivery_attempts`; against the unreachable seed URLs rows advance `PENDING → DELIVERING →
      RETRYING` with `next_retry_at` set and `attempts` incrementing (a local echo target reaches
      `DELIVERED`).
- [x] Dependency Rule holds: SSRF/WebClient/SQL live only in `delivery/adapter/*`;
      `delivery/application`/`domain` import no adapter/web/R2DBC types; `common` imports no
      context domain.
- [ ] The CI workflow run on the `feature/p-06-08-delivery-pipeline` branch is **green**.
- [ ] **Merge criteria:** all boxes above checked, CI green, no code beyond P-06–P-08 scope (no
      replay/REST endpoint, no reaper, no HMAC signing, no metrics, no schema change), and the PR
      reviewed and approved.

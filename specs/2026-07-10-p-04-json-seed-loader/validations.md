# P-04 · JSON seed loader — Validations

Each item is independently verifiable. Check only what you have actually observed.

## Application — ingest use case

- [ ] `IngestEventService` implements `IngestEventUseCase` and is a Spring `@Service`.
- [ ] `ingest(PlatformEvent)` builds a `PENDING` `Notification` with `id = event_id`,
      `attempts = 0`, `targetUrl = null`, and `createdAt`/`updatedAt` from `ClockPort.now()`,
      then calls `NotificationStorePort.save` exactly once and returns `Mono<Void>`.
- [ ] `delivery/application` imports only its own `domain` (ports + model) — no `adapter`,
      web, R2DBC, or Kafka types.

## Persistence — R2DBC store adapter

- [ ] `R2dbcNotificationRepository` implements `NotificationStorePort` using `DatabaseClient`.
- [ ] `save` issues `INSERT ... ON CONFLICT (id) DO NOTHING` and completes with the
      notification; `findById` returns the mapped aggregate (all P-02 columns; status string ↔
      enum; timestamp ↔ `Instant`) or empty when absent.
- [ ] `claimDue` and `recordAttempt` throw `UnsupportedOperationException` with a `TODO(P-07)`
      marker (deferred, not silently no-op).
- [ ] `SystemClockPort` implements `ClockPort`, returns `Instant.now()`, and is a Spring bean.
- [ ] No `.block()` anywhere in the adapter or service; reactive types throughout.

## Inbound — JSON seed loader

- [ ] `JsonSeedLoader` is an `ApplicationRunner` gated by
      `@ConditionalOnProperty("notifications.seed.enabled", matchIfMissing = true)`.
- [ ] It reads `notification_events.json`, maps each entry to a `PlatformEvent`
      (`event_id → eventId`, `delivery_date → occurredAt`), and ingests each via
      `IngestEventUseCase` without `.block()`.
- [ ] The seed's `delivery_status`/`delivery_date` do not set persisted status — every seeded
      event lands `PENDING`.
- [ ] A malformed single entry is logged and skipped without aborting startup.

## Configuration

- [ ] `notification_events.json` exists under `src/main/resources/` (bundled on the classpath).
- [ ] `application.yml` declares `notifications.seed.enabled: true` and
      `notifications.seed.location: classpath:notification_events.json`.
- [ ] `src/test/resources/application.yml` sets `notifications.seed.enabled: false`.

## Tests (one checkbox per required test)

- [ ] **[Unit]** `IngestEventServiceTest` passes — with a mocked `NotificationStorePort` and a
      fixed `ClockPort`, a `PlatformEvent` maps to a `PENDING`, zero-attempt, `targetUrl = null`
      notification stamped with `clock.now()`; `save` is called exactly once; the `Mono`
      completes.
- [ ] **[Unit]** `SeedFileMapperTest` passes — `notification_events.json` parses to 10
      `PlatformEvent`s with correct field mapping and the seed `delivery_status` is not carried
      onto the event.
- [ ] **[Integration]** `R2dbcNotificationRepositoryIT` passes (Testcontainers Postgres +
      `StepVerifier`) — `save` persists a `PENDING` row with schema defaults; `findById`
      round-trips every field; a second `save` of the same `id` with different content is a
      no-op (original row unchanged, no duplicate).
- [ ] **[Integration]** `JsonSeedLoaderIT` passes — booting with `notifications.seed.enabled=true`
      persists all 10 events as `PENDING`; a second run adds no duplicates and mutates nothing;
      with the flag `false` the loader does not run.

## CI & merge criteria

- [ ] `./gradlew build` succeeds from a clean checkout: unit tests are hermetic (no Spring /
      container), integration tests pass against Testcontainers Postgres (Docker running).
- [ ] Local smoke: `docker compose up notifications-db flyway -d`, run the app with the `local`
      profile → loader logs 10 ingested; `SELECT count(*) FROM notifications WHERE
      delivery_status = 'PENDING'` returns 10; a restart keeps it at 10 (idempotent).
- [ ] The CI workflow run on the `feature/p-04-json-seed-loader` branch is **green**.
- [ ] **Merge criteria:** all boxes above checked, CI green, no code beyond the P-04 scope
      (no subscription check, webhook, delivery/claim, endpoint, or schema change), and the PR
      reviewed and approved.

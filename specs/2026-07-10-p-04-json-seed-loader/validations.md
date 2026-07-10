# P-04 · JSON seed loader — Validations

Each item is independently verifiable. Check only what you have actually observed.

## Application — ingest use case

- [x] `IngestEventService` implements `IngestEventUseCase` and is a Spring `@Service`.
- [x] `ingest(PlatformEvent)` builds a `PENDING` `Notification` with `id = event_id`,
      `attempts = 0`, `targetUrl = null`, and `createdAt`/`updatedAt` from `ClockPort.now()`,
      then calls `NotificationStorePort.save` exactly once and returns `Mono<Void>`.
- [x] `delivery/application` imports only its own `domain` (ports + model) — no `adapter`,
      web, R2DBC, or Kafka types.

## Persistence — R2DBC store adapter

- [x] `R2dbcNotificationRepository` implements `NotificationStorePort` using `DatabaseClient`.
- [x] `save` issues `INSERT ... ON CONFLICT (id) DO NOTHING` and completes with the
      notification; `findById` returns the mapped aggregate (all P-02 columns; status string ↔
      enum; timestamp ↔ `Instant`) or empty when absent.
- [x] `claimDue` and `recordAttempt` throw `UnsupportedOperationException` with a `TODO(P-07)`
      marker (deferred, not silently no-op).
- [x] `SystemClockPort` implements `ClockPort`, returns `Instant.now()`, and is a Spring bean.
- [x] No `.block()` anywhere in the adapter or service; reactive types throughout.

## Inbound — JSON seed loader

- [x] `JsonSeedLoader` is an `ApplicationRunner` gated by
      `@ConditionalOnProperty("notifications.seed.enabled", matchIfMissing = true)`.
- [x] It reads `notification_events.json`, maps each entry to a `PlatformEvent`
      (`event_id → eventId`, `delivery_date → occurredAt`), and ingests each via
      `IngestEventUseCase` without `.block()`.
- [x] The seed's `delivery_status`/`delivery_date` do not set persisted status — every seeded
      event lands `PENDING`.
- [x] A malformed single entry is logged and skipped without aborting startup.

## Configuration

- [x] `notification_events.json` exists under `src/main/resources/` (bundled on the classpath).
- [x] `application.yml` declares `notifications.seed.enabled: true` and
      `notifications.seed.location: classpath:notification_events.json`.
- [x] `src/test/resources/application.yml` sets `notifications.seed.enabled: false`.

## Tests (one checkbox per required test)

- [x] **[Unit]** `IngestEventServiceTest` passes — with a mocked `NotificationStorePort` and a
      fixed `ClockPort`, a `PlatformEvent` maps to a `PENDING`, zero-attempt, `targetUrl = null`
      notification stamped with `clock.now()`; `save` is called exactly once; the `Mono`
      completes.
- [x] **[Unit]** `SeedFileMapperTest` passes — `notification_events.json` parses to 10
      `PlatformEvent`s with correct field mapping and the seed `delivery_status` is not carried
      onto the event.
- [x] **[Integration]** `R2dbcNotificationRepositoryIT` passes (Testcontainers Postgres +
      `StepVerifier`) — `save` persists a `PENDING` row with schema defaults; `findById`
      round-trips every field; a second `save` of the same `id` with different content is a
      no-op (original row unchanged, no duplicate).
- [x] **[Integration]** `JsonSeedLoaderIT` passes — booting with `notifications.seed.enabled=true`
      persists all 10 events as `PENDING`; a second run adds no duplicates and mutates nothing;
      with the flag `false` the loader does not run.

## CI & merge criteria

- [x] `./gradlew build` succeeds from a clean checkout: unit tests are hermetic (no Spring /
      container), integration tests pass against Testcontainers Postgres (Docker running).
- [x] Local smoke: `docker compose up notifications-db flyway -d`, run the app with the `local`
      profile → loader logs 10 ingested; `SELECT count(*) FROM notifications WHERE
      delivery_status = 'PENDING'` returns 10; a restart keeps it at 10 (idempotent).
- [ ] The CI workflow run on the `feature/p-04-json-seed-loader` branch is **green**.
- [ ] **Merge criteria:** all boxes above checked, CI green, no code beyond the P-04 scope
      (no subscription check, webhook, delivery/claim, endpoint, or schema change), and the PR
      reviewed and approved.

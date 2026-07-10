# P-04 · JSON seed loader — Plan

Tasks grouped by the layers this phase actually touches. P-04 is the **first ingestion
slice**: it wires the `delivery` hexagon's `IngestEventUseCase` to a real persistence
adapter and feeds it from a JSON driving adapter. It builds directly on P-03 (domain +
ports) and P-02 (schema + R2DBC). **No subscription check yet** (P-05), **no webhook/
delivery** (P-06+), **no new Flyway migration** (the `notifications` table already exists).

Base package: `com.cobre.notifications`. Everything lands in the `delivery` context.

Scope of the store adapter this phase: implement `save` + `findById` fully (with idempotent
`INSERT ... ON CONFLICT (id) DO NOTHING`); leave `claimDue` and `recordAttempt` as
clearly-marked `UnsupportedOperationException` stubs — they belong to P-07/P-08.

## Backend — application (`delivery/application/`)

- [ ] `IngestEventService implements IngestEventUseCase` — the use-case implementation.
      `ingest(PlatformEvent)`: builds `Notification.pending(event.eventId(), event.clientId(),
      event.eventType(), event.content(), null /* targetUrl resolved in P-05 */,
      clock.now())` and delegates to `NotificationStorePort.save(...)`, returning
      `Mono<Void>`. Idempotency is delegated to the store's `ON CONFLICT DO NOTHING`, so
      re-ingesting the same `event_id` is a harmless no-op. Depends only on
      `NotificationStorePort` and `ClockPort` (never on an adapter). Annotated `@Service`.

## Backend — outbound adapter / persistence (`delivery/adapter/out/`)

- [ ] `R2dbcNotificationRepository implements NotificationStorePort` — R2DBC adapter built on
      `DatabaseClient` (raw SQL, because both `ON CONFLICT DO NOTHING` here and the later
      `FOR UPDATE SKIP LOCKED` in P-07 are not expressible through a derived Spring Data
      method). Annotated `@Repository`/`@Component`.
  - `save(Notification)` — `INSERT INTO notifications (id, client_id, event_type, content,
    target_url, delivery_status, attempts, next_retry_at, claimed_at, last_error,
    created_at, delivered_at, updated_at) VALUES (...) ON CONFLICT (id) DO NOTHING`; returns
    `Mono<Notification>` completing with the notification (existing row untouched on
    conflict).
  - `findById(String)` — `SELECT * FROM notifications WHERE id = :id`; maps the row back to a
    `Notification` via the canonical constructor (`delivery_status` string → `DeliveryStatus`
    enum; `TIMESTAMPTZ` → `Instant`); empty `Mono` when absent.
  - `claimDue(Instant, int)` / `recordAttempt(DeliveryAttempt)` — throw
    `UnsupportedOperationException("implemented in P-07")` with a `// TODO(P-07)` marker.
- [ ] `SystemClockPort implements ClockPort` — production `ClockPort` bean returning
      `Instant.now()`. Lives in `delivery/adapter/out/` (the outbound side); tests bind a
      fixed instant instead. Annotated `@Component`.

## Backend — inbound adapter / seed loader (`delivery/adapter/in/`)

- [ ] `JsonSeedLoader implements ApplicationRunner` — on startup, reads the seed resource,
      parses each entry into a `PlatformEvent`, and calls `IngestEventUseCase.ingest(...)` for
      each, composed reactively (`Flux.fromIterable(events).concatMap(useCase::ingest)`; no
      `.block()`). Logs a start/summary line (count ingested) with `event_id` context. Gated
      by `@ConditionalOnProperty(name = "notifications.seed.enabled", havingValue = "true",
      matchIfMissing = true)`.
- [ ] `SeedFile` DTO + mapping — a Jackson-bound view of `notification_events.json`
      (`event_id`, `event_type`, `content`, `delivery_date`, `delivery_status`, `client_id`)
      mapped to `PlatformEvent(eventId, eventType, content, clientId, occurredAt =
      delivery_date)`. **`delivery_status` and any prior state are ignored** — every seeded
      event is (re)ingested as `PENDING`; the seed's `completed`/`failed` is not honored at
      ingest. Malformed individual entries are logged and skipped, not fatal.

## Configuration & wiring (`src/main/resources/`)

- [ ] Copy `specs/notification_events.json` → `src/main/resources/notification_events.json`
      so it ships on the classpath (self-contained jar / Docker image).
- [ ] `application.yml` — add `notifications.seed.enabled: true` and
      `notifications.seed.location: classpath:notification_events.json` (override-able via
      env, 12-factor).
- [ ] `src/test/resources/application.yml` — set `notifications.seed.enabled: false` so
      `@SpringBootTest` contexts do not auto-seed; the loader integration test opts back in
      explicitly.

## Tests

- [ ] **[Unit]** `IngestEventServiceTest` — with a mocked `NotificationStorePort` and a fixed
      `ClockPort`: a `PlatformEvent` maps to a `Notification` that is `PENDING`, `attempts = 0`,
      `targetUrl == null`, `createdAt == clock.now()`, and carries the event's `id`/`clientId`/
      `eventType`/`content`; `save` is invoked exactly once with that notification; the
      returned `Mono<Void>` completes.
- [ ] **[Unit]** `SeedFileMapperTest` — parsing `notification_events.json` yields 10
      `PlatformEvent`s with correct field mapping (`event_id → eventId`, `delivery_date →
      occurredAt`), and the seed's `delivery_status` is **not** carried onto the event (ingest
      always starts `PENDING`).
- [ ] **[Integration]** `R2dbcNotificationRepositoryIT` (`@SpringBootTest` + Testcontainers
      Postgres + `StepVerifier`) — `save` persists a `PENDING` row with schema defaults
      applied; `findById` round-trips every field; a second `save` of the **same `id`** with
      different content is a no-op (`ON CONFLICT DO NOTHING` — original row unchanged, no
      duplicate). Pins down the idempotency contract against real Postgres.
- [ ] **[Integration]** `JsonSeedLoaderIT` (`@SpringBootTest` with
      `notifications.seed.enabled=true` + Testcontainers Postgres) — booting the context runs
      the loader and persists all **10** events as `PENDING`; a second run (or restart) adds
      **no** duplicates and mutates nothing (idempotent ingest end to end: file → use case →
      DB). A companion assertion confirms that with the flag `false` the loader does not run.

## Smoke test & wrap-up

- [ ] Work through `validations.md` top to bottom; check off each item as observed.
- [ ] `./gradlew build` green from a clean checkout (unit tests hermetic; integration tests
      pass against Testcontainers, Docker running).
- [ ] `docker compose up notifications-db flyway -d`, then run the app with the `local`
      profile; confirm the loader logs 10 ingested events and `SELECT count(*) FROM
      notifications WHERE delivery_status = 'PENDING'` returns 10; restart and confirm the
      count stays 10 (idempotent).
- [ ] Confirm the Dependency Rule still holds: `delivery/application` imports only its own
      `domain` (ports + model), never an `adapter`; `delivery/domain` unchanged.
- [ ] Open the PR; confirm CI is green.

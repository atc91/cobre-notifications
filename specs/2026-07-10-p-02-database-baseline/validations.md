# P-02 · Database baseline — Validations

Each item is independently verifiable. Check only what you have actually observed.

## Build & dependencies

- [x] `build.gradle.kts` declares `spring-boot-starter-data-r2dbc` (implementation) and
      `org.postgresql:r2dbc-postgresql` (runtimeOnly); no JDBC driver on the **main** classpath.
- [x] Flyway + JDBC are **test-scope only**: `spring-boot-starter-jdbc`, `flyway-core`,
      `spring-boot-flyway`, `flyway-database-postgresql` (testRuntimeOnly), `postgresql`
      (testRuntimeOnly).
- [x] Testcontainers deps present in test scope: `spring-boot-testcontainers`,
      `testcontainers-postgresql`, `testcontainers-r2dbc`.

## Schema (migrations)

- [x] `src/main/flyway/` contains `V1__create_subscriptions.sql`,
      `V2__create_notifications.sql`, and `V3__create_delivery_attempts.sql`.
- [x] `notifications.id` is `VARCHAR PRIMARY KEY` (the platform `event_id`, e.g. `EVT001`),
      **not** a UUID.
- [x] `notifications.delivery_status` is `VARCHAR` with a `CHECK` over
      `PENDING/DELIVERING/RETRYING/DELIVERED/FAILED` and defaults to `PENDING`;
      `attempts` defaults to `0`. _(CHECK verified by `deliveryStatusCheckConstraintRejectsUnknownValue`;
      defaults verified by `notificationRoundTrips`.)_
- [x] `notifications` insert-time columns are `NOT NULL` (`id`, `client_id`, `event_type`,
      `content`, `created_at`, `updated_at`); later-populated columns are nullable
      (`target_url`, `next_retry_at`, `claimed_at`, `last_error`, `delivered_at`).
- [x] `subscriptions` has a unique constraint on `(client_id, event_type)` and
      `active BOOLEAN NOT NULL DEFAULT true`.
- [x] `delivery_attempts.notification_id` is `NOT NULL REFERENCES notifications(id)`.
- [x] Indexes exist on `notifications (delivery_status, next_retry_at)`,
      `notifications (client_id, created_at)`, and `delivery_attempts (notification_id)`.

## Wiring & config

- [x] `application.yml` has a `spring.r2dbc` block (`url`, `username`, `password`) sourced
      from env vars with localhost defaults; no Flyway config in the main resources.
- [x] `application-local.yml` enables `org.springframework.r2dbc` /
      `io.r2dbc.postgresql.QUERY` at `DEBUG`.
- [x] `src/test/resources/application.yml` sets
      `spring.flyway.locations: filesystem:src/main/flyway`.
- [x] `.env.example` lists the DB variables the R2DBC block reads
      (`POSTGRES_DB`/`POSTGRES_USER`/`POSTGRES_PASSWORD`), consistent with Compose.
- [x] Running the app with `-Dspring.profiles.active=local` against
      `docker compose up notifications-db -d` connects via R2DBC (SQL debug logging appears —
      `io.r2dbc.postgresql.QUERY` probes run against Postgres) and `/actuator/health` returns
      HTTP 200 `{"status":"UP"}`. _(Requires a local `.env` copied from `.env.example`.)_
- [x] `docker compose up flyway --exit-code-from flyway` applies all three migrations and
      exits 0; `\dt` on `notifications-db` then shows `subscriptions`, `notifications`,
      `delivery_attempts` (plus `flyway_schema_history`).
- [x] `docker compose up -d --build` brings up the full stack in order (DB healthy → flyway
      migrates and exits 0 → `notifications-app` starts); the app reaches `healthy` and
      `curl http://localhost:8080/actuator/health` returns HTTP 200 `{"status":"UP"}`.

## Tests (one checkbox per required test)

- [x] **[Integration]** `schemaLoads` passes — after Flyway applies the migrations,
      `subscriptions`, `notifications`, and `delivery_attempts` are all present
      (`information_schema.tables` via `DatabaseClient` + `StepVerifier`).
- [x] **[Integration]** `notificationRoundTrips` passes — a `PENDING` notification inserted
      via R2DBC reads back with defaults applied (`attempts = 0`, `created_at` set,
      `delivery_status = 'PENDING'`).
- [x] **[Integration]** `deliveryStatusCheckConstraintRejectsUnknownValue` passes —
      inserting an out-of-set `delivery_status` fails the `CHECK` constraint.

## CI & merge criteria

- [x] `./gradlew build` succeeds from a clean checkout with Docker running (all integration
      tests green — 2 app + 3 schema, 0 failures).
- [ ] The CI workflow run on the `feature/p-02-database-baseline` branch is **green**
      (Testcontainers available on the runner).
- [ ] **Merge criteria:** all boxes above checked, CI green, no domain/entity/repository or
      business-logic code introduced (those belong to P-03/P-04), and the PR reviewed and approved.

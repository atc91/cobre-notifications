# P-02 · Database baseline — Validations

Each item is independently verifiable. Check only what you have actually observed.

## Build & dependencies

- [ ] `build.gradle.kts` declares `spring-boot-starter-data-r2dbc` (implementation) and
      `org.postgresql:r2dbc-postgresql` (runtimeOnly); no JDBC driver on the **main** classpath.
- [ ] Flyway + JDBC are **test-scope only**: `spring-boot-starter-jdbc`, `flyway-core`,
      `spring-boot-flyway`, `flyway-database-postgresql` (testRuntimeOnly), `postgresql`
      (testRuntimeOnly).
- [ ] Testcontainers deps present in test scope: `spring-boot-testcontainers`,
      `testcontainers-postgresql`, `testcontainers-r2dbc`.

## Schema (migrations)

- [ ] `src/main/flyway/` contains `V1__create_subscriptions.sql`,
      `V2__create_notifications.sql`, and `V3__create_delivery_attempts.sql`.
- [ ] `notifications.id` is `VARCHAR PRIMARY KEY` (the platform `event_id`, e.g. `EVT001`),
      **not** a UUID.
- [ ] `notifications.delivery_status` is `VARCHAR` with a `CHECK` over
      `PENDING/DELIVERING/RETRYING/DELIVERED/FAILED` and defaults to `PENDING`;
      `attempts` defaults to `0`.
- [ ] `notifications` insert-time columns are `NOT NULL` (`id`, `client_id`, `event_type`,
      `content`, `created_at`, `updated_at`); later-populated columns are nullable
      (`target_url`, `next_retry_at`, `claimed_at`, `last_error`, `delivered_at`).
- [ ] `subscriptions` has a unique constraint on `(client_id, event_type)` and
      `active BOOLEAN NOT NULL DEFAULT true`.
- [ ] `delivery_attempts.notification_id` is `NOT NULL REFERENCES notifications(id)`.
- [ ] Indexes exist on `notifications (delivery_status, next_retry_at)`,
      `notifications (client_id, created_at)`, and `delivery_attempts (notification_id)`.

## Wiring & config

- [ ] `application.yml` has a `spring.r2dbc` block (`url`, `username`, `password`) sourced
      from env vars with localhost defaults; no Flyway config in the main resources.
- [ ] `application-local.yml` enables `org.springframework.r2dbc` /
      `io.r2dbc.postgresql.QUERY` at `DEBUG`.
- [ ] `src/test/resources/application.yml` sets
      `spring.flyway.locations: filesystem:src/main/flyway`.
- [ ] `.env.example` lists the DB variables the R2DBC block reads
      (`POSTGRES_DB`/`POSTGRES_USER`/`POSTGRES_PASSWORD`), consistent with Compose.
- [ ] Running the app with `-Dspring.profiles.active=local` against
      `docker compose up notifications-db -d` connects via R2DBC (SQL debug logging appears)
      and `/actuator/health` returns `UP`.

## Tests (one checkbox per required test)

- [ ] **[Integration]** `schemaLoads` passes — after Flyway applies the migrations,
      `subscriptions`, `notifications`, and `delivery_attempts` are all present
      (`information_schema.tables` via `DatabaseClient` + `StepVerifier`).
- [ ] **[Integration]** `notificationRoundTrips` passes — a `PENDING` notification inserted
      via R2DBC reads back with defaults applied (`attempts = 0`, `created_at` set,
      `delivery_status = 'PENDING'`).
- [ ] **[Integration]** `deliveryStatusCheckConstraintRejectsUnknownValue` passes —
      inserting an out-of-set `delivery_status` fails the `CHECK` constraint.

## CI & merge criteria

- [ ] `./gradlew build` succeeds from a clean checkout with Docker running (all integration
      tests green).
- [ ] The CI workflow run on the `feature/p-02-database-baseline` branch is **green**
      (Testcontainers available on the runner).
- [ ] **Merge criteria:** all boxes above checked, CI green, no domain/entity/repository or
      business-logic code introduced (those belong to P-03/P-04), and the PR reviewed and approved.

# P-02 · Database baseline — Plan

Tasks grouped by the layers this phase actually touches: build/dependencies, the SQL
schema, configuration/wiring, tests, and wrap-up. No frontend and no domain/adapter code
(those are P-03/P-04). Reference config mirrored from matchly `platform-api`.

## Backend & build (`build.gradle.kts`)

- [ ] Add `implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")`.
- [ ] Add `runtimeOnly("org.postgresql:r2dbc-postgresql")`.
- [ ] Add test-scope Flyway + JDBC deps: `spring-boot-starter-jdbc`, `flyway-core`,
      `spring-boot-flyway`, `testRuntimeOnly("org.flywaydb:flyway-database-postgresql")`,
      `testRuntimeOnly("org.postgresql:postgresql")`.
- [ ] Add test-scope Testcontainers deps: `spring-boot-testcontainers`,
      `testcontainers-postgresql`, `testcontainers-r2dbc` (reactor-test is already present).

## Database (`src/main/flyway/`)

- [ ] `V1__create_subscriptions.sql` — `subscriptions` (`id UUID PK DEFAULT uuidv7()`,
      `client_id`, `event_type`, `target_url`, `secret` nullable, `active` default true,
      `created_at`), unique `(client_id, event_type)`.
- [ ] `V2__create_notifications.sql` — `notifications` with `id VARCHAR PK` (= `event_id`),
      `client_id`, `event_type`, `content TEXT`, nullable `target_url`,
      `delivery_status VARCHAR DEFAULT 'PENDING'` + `CHECK`
      (`PENDING/DELIVERING/RETRYING/DELIVERED/FAILED`), `attempts INT DEFAULT 0`,
      nullable `next_retry_at`/`claimed_at`/`last_error`/`delivered_at`,
      `created_at`/`updated_at`; indexes on `(delivery_status, next_retry_at)` and
      `(client_id, created_at)`.
- [ ] `V3__create_delivery_attempts.sql` — `delivery_attempts` (`id UUID PK DEFAULT uuidv7()`,
      `notification_id VARCHAR NOT NULL REFERENCES notifications(id)`, `attempt_no`,
      `attempted_at`, nullable `http_status`/`error`/`duration_ms`); index on `(notification_id)`.

## Configuration & wiring

- [ ] `src/main/resources/application.yml` — add `spring.r2dbc` block (`url`, `username`,
      `password`) with env-var sourcing and localhost defaults pointing at `notifications-db`.
- [ ] `src/main/resources/application-local.yml` — enable SQL debug logging
      (`org.springframework.r2dbc`, `io.r2dbc.postgresql.QUERY` at `DEBUG`); remove the
      "DB credentials arrive in P-02" placeholder note.
- [ ] `src/test/resources/application.yml` — set
      `spring.flyway.locations: filesystem:src/main/flyway`.
- [ ] `.env.example` — confirm/align `POSTGRES_DB`/`POSTGRES_USER`/`POSTGRES_PASSWORD` with
      the R2DBC url/username/password the app now reads.
- [ ] Confirm `docker-compose.yml` `notifications-db` matches the R2DBC target (host, port,
      db name); no change if already aligned.

## Tests

- [ ] **[Integration]** `TestcontainersConfiguration` — `@TestConfiguration` bean:
      `@ServiceConnection PostgreSQLContainer("postgres:18-alpine")` (mirrors matchly).
- [ ] **[Integration]** `schemaLoads` — `@SpringBootTest` + `@Import(TestcontainersConfiguration)`;
      after Flyway applies the migrations, assert `subscriptions`, `notifications`, and
      `delivery_attempts` all exist (query `information_schema.tables` via `DatabaseClient`,
      verified with `StepVerifier`). Proves Flyway ran and R2DBC connects.
- [ ] **[Integration]** `notificationRoundTrips` — insert a `PENDING` notification and read it
      back via `DatabaseClient`; assert defaults are applied (`attempts = 0`, `created_at`
      populated, `delivery_status = 'PENDING'`). Proves R2DBC read/write against the schema.
- [ ] **[Integration]** `deliveryStatusCheckConstraintRejectsUnknownValue` — inserting a
      notification with a `delivery_status` outside the allowed set fails; pins down the
      `VARCHAR + CHECK` design decision.

## Smoke test & wrap-up

- [ ] Work through `validations.md` top to bottom; check off each item as observed.
- [ ] `./gradlew build` green from a clean checkout (Flyway + Testcontainers integration
      tests pass, Docker running).
- [ ] `docker compose up notifications-db -d`, then run the app with the `local` profile and
      confirm it connects to Postgres via R2DBC (SQL debug logging visible; `/actuator/health`
      still `UP`).
- [ ] Open the PR; confirm CI is green.

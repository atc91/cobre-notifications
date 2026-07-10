# P-02 · Database baseline — Requirements

## Scope

This phase establishes the persistence baseline for the service: the PostgreSQL
schema for the three tables the domain owns (`subscriptions`, `notifications`,
`delivery_attempts`), the R2DBC connection wiring that lets the running app talk to
Postgres, and the Flyway-at-test-time setup that applies the schema against a real
Testcontainers Postgres. It delivers **schema + connectivity only**: one integration
test proves the migrations apply and R2DBC can read/write the schema.

It explicitly does **not** introduce domain entities, `@Table` mapping classes,
`R2dbcRepository` interfaces, ports, or any business logic — those belong to P-03
(domain model + ports) and P-04+ (the R2DBC adapters). It also introduces **no seed
or reference data**: subscription rows arrive with P-05 and notification rows with
the P-04 seed loader.

Per the architecture, **Flyway runs at test time only** (JDBC, against Testcontainers);
the production container uses **R2DBC exclusively** and assumes the schema already exists.

## Functional requirements

1. The build declares R2DBC at runtime: `spring-boot-starter-data-r2dbc` (implementation)
   and `org.postgresql:r2dbc-postgresql` (runtimeOnly). No JDBC driver on the main
   classpath.
2. The build declares Flyway + Testcontainers **in the test scope only**:
   `spring-boot-starter-jdbc`, `org.flywaydb:flyway-core`,
   `org.springframework.boot:spring-boot-flyway`, `flyway-database-postgresql`
   (testRuntimeOnly), `org.postgresql:postgresql` (testRuntimeOnly),
   `spring-boot-testcontainers`, `testcontainers-postgresql`, `testcontainers-r2dbc`.
3. `src/main/flyway/` contains three ordered migrations that create the schema:
   - **`V1__create_subscriptions.sql`** — `subscriptions` table.
   - **`V2__create_notifications.sql`** — `notifications` table (the aggregate root).
   - **`V3__create_delivery_attempts.sql`** — `delivery_attempts` table, FK → `notifications`.
4. **`subscriptions`** columns: `id UUID PK DEFAULT uuidv7()`, `client_id VARCHAR NOT NULL`,
   `event_type VARCHAR NOT NULL` (a concrete type or `'*'`), `target_url VARCHAR NOT NULL`,
   `secret VARCHAR` (nullable; per-subscription HMAC, used later),
   `active BOOLEAN NOT NULL DEFAULT true`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`,
   with a unique constraint on `(client_id, event_type)`.
5. **`notifications`** columns: `id VARCHAR PK` (**= platform `event_id`**, the idempotency
   key — not a UUID), `client_id VARCHAR NOT NULL`, `event_type VARCHAR NOT NULL`,
   `content TEXT NOT NULL`, `target_url VARCHAR` (nullable; resolved from the subscription
   at ingest), `delivery_status VARCHAR NOT NULL DEFAULT 'PENDING'`,
   `attempts INT NOT NULL DEFAULT 0`, `next_retry_at TIMESTAMPTZ` (nullable),
   `claimed_at TIMESTAMPTZ` (nullable; delivery lease), `last_error TEXT` (nullable),
   `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `delivered_at TIMESTAMPTZ` (nullable),
   `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`.
6. `delivery_status` is a **`VARCHAR` + `CHECK`** constraint over
   `('PENDING','DELIVERING','RETRYING','DELIVERED','FAILED')` — not a PG enum — so new
   statuses are a plain `ALTER` and no R2DBC codec wiring is needed.
7. **`delivery_attempts`** columns (append-only audit): `id UUID PK DEFAULT uuidv7()`,
   `notification_id VARCHAR NOT NULL REFERENCES notifications(id)`, `attempt_no INT NOT NULL`,
   `attempted_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `http_status INT` (nullable),
   `error TEXT` (nullable), `duration_ms BIGINT` (nullable).
8. Indexes back the known access patterns:
   - `notifications (delivery_status, next_retry_at)` — the due-work poll (P-07).
   - `notifications (client_id, created_at)` — the client-scoped list endpoint (P-09).
   - `delivery_attempts (notification_id)` — per-notification audit lookup.
9. `application.yml` gains a `spring.r2dbc` block (`url`, `username`, `password`) sourced
   from environment variables with local-friendly defaults
   (`r2dbc:postgresql://localhost:5432/...`), consistent with the 12-factor / `.env` setup.
10. The `local` profile enables SQL debug logging
    (`org.springframework.r2dbc` / `io.r2dbc.postgresql.QUERY` at `DEBUG`).
11. A test-scoped `application.yml` sets `spring.flyway.locations: filesystem:src/main/flyway`
    so Flyway applies the migrations before the R2DBC tests run.
12. `.env.example` reflects any DB variables the R2DBC block reads (reusing the existing
    `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` already used by Compose).
13. `docker-compose.yml` gains a one-shot **`flyway`** migration service that applies the
    `src/main/flyway` migrations to `notifications-db`. Because Flyway is test-scope in the
    build and the app image connects via R2DBC only, `docker compose up` would otherwise
    start an **empty** database; the migration service makes local runs functional and
    mirrors the production model (schema applied by a separate out-of-band job, never by the
    app). It `depends_on` the DB being healthy, runs `migrate`, and exits.
14. `docker-compose.yml` gains a **`notifications-app`** service built from the `Dockerfile`.
    It `depends_on` `notifications-db` (healthy) and `flyway` (completed successfully), exposes
    `8080`, connects to the DB over the Compose network via `SPRING_R2DBC_URL`
    (`r2dbc:postgresql://notifications-db:5432/...`), and has an `/actuator/health`
    healthcheck. `docker compose up` brings up the whole stack: DB → migrations → app.

## Non-functional requirements

- **Data integrity (OWASP A08 / A03 support):** referential integrity via the
  `delivery_attempts → notifications` FK, and value integrity via the `delivery_status`
  `CHECK` constraint, are enforced at the schema level.
- **Split-readiness:** table ownership is honored — no cross-context concerns leak into the
  schema; each table maps to exactly one owning context (`subscription` / `delivery`).
- **Determinism in tests:** the schema is applied by Flyway against an ephemeral
  Testcontainers Postgres (`postgres:18-alpine`), so tests are hermetic and repeatable.

## Out of scope

- Domain model, value objects, ports (`P-03`).
- R2DBC repositories / `@Table` entities / outbound-port adapters (`P-04+`).
- Seed data of any kind — notification seeding (`P-04`), subscription seeding (`P-05`).
- Business logic: ingest, delivery, retry, query, replay, SSRF guard, auth.
- Any production-time schema management (Flyway is test-scope only; prod assumes the
  schema exists and connects via R2DBC).

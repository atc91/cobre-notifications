# cobre-notifications

Subscription-gated, reliable delivery of Cobre platform events to client webhooks, with a
full queryable audit trail and a self-service REST API.

Spring Boot 4.1.0 reactive service (WebFlux + Actuator), Java 25, Gradle 9.6.0.

## Architecture

The service **persists first and delivers later**: events are gated against the client's
subscription, stored in Postgres, then a scheduler POSTs them to the client's webhook behind
an SSRF guard, retrying with exponential backoff until delivered or dead-lettered. It is built
as three hexagonal (ports & adapters) bounded contexts in one split-ready deployable.

📐 **[`specs/architecture.md`](specs/architecture.md)** — the solution proposal, an ASCII
overview diagram, the domain model, the dual-write/retry design, security (OWASP), and
observability. See also [`specs/mission.md`](specs/mission.md) (the problem & goals) and
[`specs/roadmap.md`](specs/roadmap.md) (the phased plan).

> **Status — self-service API complete (P-11).** Ingestion (JSON seed → subscription-gated
> persistence), the delivery worker (claim due rows → SSRF-guarded webhook POST →
> retry/back-off → dead-letter), and the three mandated self-service endpoints
> (`GET /notification_events`, `GET /notification_events/{id}`,
> `POST /notification_events/{id}/replay`, client-scoped via an `X-Client-Id` header) are in
> place. Next up: hardening — authentication & access control (P-12). See
> [`specs/roadmap.md`](specs/roadmap.md).

## Prerequisites

- JDK 25 (Temurin recommended)
- Docker (local PostgreSQL via Compose, and Testcontainers for the integration tests)

## Build

```bash
./gradlew build
```

Compiles the project and runs the tests. The integration tests spin up PostgreSQL (and a
stub webhook server) via Testcontainers, so **Docker must be running**.

## Run locally

Start PostgreSQL first (see [Local Postgres](#local-postgres) below), then:

```bash
./gradlew bootRun
# or with the local profile (debug logging):
./gradlew bootRun --args='--spring.profiles.active=local'
```

The service starts on port **8080** (Netty/WebFlux). On startup it seeds subscriptions and
events, then the delivery scheduler begins POSTing due notifications to their webhooks.

## Verify

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

## Run tests

```bash
./gradlew test
```

## Docker

```bash
docker build -t cobre-notifications .
docker run -p 8080:8080 cobre-notifications
```

## Local Postgres

```bash
cp .env.example .env          # edit credentials if desired
docker compose up notifications-db flyway -d
```

Brings up `postgres:18-alpine` on `localhost:5432` and applies the Flyway migrations. The
application connects to it over R2DBC at startup.

## Configuration

| File | Purpose |
|---|---|
| `application.yml` | Base config: app name, port 8080, Actuator `health`/`info` exposure |
| `application-local.yml` | Local dev overrides; debug logging for `com.cobre.notifications` |

Activate the local profile with `-Dspring.profiles.active=local` or
`SPRING_PROFILES_ACTIVE=local`. Personal overrides can go in a gitignored
`application-local.local.yml`.

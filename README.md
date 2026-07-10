# cobre-notifications

Subscription-gated, reliable delivery of Cobre platform events to client webhooks, with a
full queryable audit trail and a self-service REST API. See [`specs/`](specs/) for the
mission, architecture, and phased roadmap.

Spring Boot 4.1.0 reactive service (WebFlux + Actuator), Java 25, Gradle 9.6.0.

> **Status — P-01 (project scaffold).** This is the bootable foundation: a WebFlux shell
> with `/actuator/health` and the hexagonal package skeleton. Database wiring (R2DBC +
> Flyway) arrives in P-02, so the app does not yet connect to Postgres — the Compose
> service below is staged for that phase.

## Prerequisites

- JDK 25 (Temurin recommended)
- Docker (for the local PostgreSQL instance, used from P-02 onward)

## Build

```bash
./gradlew build
```

Compiles the project and runs the integration tests. No database is required — the context
loads without one in this phase.

## Run locally

```bash
./gradlew bootRun
# or with the local profile (debug logging):
./gradlew bootRun --args='--spring.profiles.active=local'
```

The service starts on port **8080** (Netty/WebFlux).

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

## Local Postgres (staged for P-02)

```bash
cp .env.example .env          # edit credentials if desired
docker compose up notifications-db -d
```

Brings up `postgres:18-alpine` on `localhost:5432`. The application does not consume it yet.

## Configuration

| File | Purpose |
|---|---|
| `application.yml` | Base config: app name, port 8080, Actuator `health`/`info` exposure |
| `application-local.yml` | Local dev overrides; debug logging for `com.cobre.notifications` |

Activate the local profile with `-Dspring.profiles.active=local` or
`SPRING_PROFILES_ACTIVE=local`. Personal overrides can go in a gitignored
`application-local.local.yml`.

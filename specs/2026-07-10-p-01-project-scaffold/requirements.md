# P-01 · Project scaffold — Requirements

## Scope

P-01 delivers the **buildable, bootable foundation** of `cobre-notifications` and nothing
more. It produces a Spring Boot 4.1.0 WebFlux service (Java 25, Gradle 9.6.0 Kotlin DSL)
that starts on port 8080 and answers `/actuator/health`; the hexagonal package skeleton for
the `delivery`, `subscription`, `query`, and `common` contexts with the three-layer
(`domain`/`application`/`adapter`) sub-structure in place; a `docker-compose.yml` that stages
a `postgres:18-alpine` service; a multi-stage `Dockerfile`; and a GitHub Actions CI gate that
runs `./gradlew build`.

It explicitly does **not** wire the database. There is no `data-r2dbc` dependency, no
`spring.r2dbc` configuration, no Flyway, no migrations, no schema, and no DB component in the
health response. The Postgres service exists in Compose only to be *consumed by P-02*. No
domain model, no ports, no adapters with behaviour, no endpoints beyond Actuator, and no
security exist yet — each is the subject of a later phase.

## Functional requirements

1. `./gradlew build` compiles the project and runs its tests successfully from a clean
   checkout, using the committed Gradle wrapper pinned to **9.6.0**.
2. The build declares Spring Boot **4.1.0**, targets a Java **25** toolchain, group
   `com.cobre`, and depends only on `spring-boot-starter-webflux`,
   `spring-boot-starter-actuator`, and `spring-boot-starter-validation` (plus test
   dependencies). No `data-r2dbc`, security, or persistence dependency is present.
3. `NotificationsApplication` is a `@SpringBootApplication` that starts the reactive
   (Netty/WebFlux) stack on port **8080**.
4. The application context loads successfully **without any database available**.
5. `GET /actuator/health` returns HTTP **200** with body `{"status":"UP"}`, and the health
   response contains **no** `r2dbc`/`db` component in this phase.
6. The Java source tree contains the four context packages under
   `com.cobre.notifications` — `common`, `delivery`, `subscription`, `query` — with
   `delivery`/`subscription`/`query` each exposing the `domain` (`model`, `port/in`,
   `port/out`), `application`, and `adapter` (`in`, `out`) sub-packages, anchored by
   `package-info.java` markers so the layout is committed and documented.
7. A multi-stage `Dockerfile` builds a runnable boot jar on `eclipse-temurin:25-jdk-alpine`
   and runs it on `eclipse-temurin:25-jre-alpine`, exposing port 8080.
8. `docker-compose.yml` defines a single `notifications-db` service (`postgres:18-alpine`)
   with `POSTGRES_DB/USER/PASSWORD` sourced from the environment and a `pg_isready`
   healthcheck; `docker compose up notifications-db -d` brings it to a healthy state.
9. A `.env.example` documents the Postgres environment variables used by Compose.
10. `.github/workflows/ci.yml` runs on `push` and `pull_request`, sets up Temurin JDK 25,
    and executes `./gradlew build`, failing the check if compilation or tests fail.

## Non-functional requirements

1. **Reactive purity (forward-looking constraint).** The scaffold uses the WebFlux/Netty
   stack exclusively — no `spring-boot-starter-web` (Tomcat/MVC) on the classpath — so the
   `.block()`-free reactive rule holds from the first commit.
2. **12-factor config.** The base `application.yml` reads no hardcoded secrets; the `local`
   profile carries developer-friendly overrides only. Compose credentials come from the
   environment via `.env`.
3. **Reproducible build.** The Gradle wrapper is committed and version-pinned so CI and any
   developer build against the identical Gradle version.

## Out of scope

- R2DBC wiring, connection configuration, and any DB health indicator — **P-02**.
- Flyway migrations, the `notifications`/`delivery_attempts`/`subscriptions` schema — **P-02**.
- Domain model, value objects, and port interfaces — **P-03**.
- JSON seed loader / ingestion — **P-04+**.
- Webhook client, delivery scheduler, retry engine — **P-06–P-08**.
- The three self-service REST endpoints — **P-09–P-11**.
- Authentication, authorization, rate limiting (OWASP hardening) — **P-12**.
- Metrics, structured logging, tracing, alerts — **P-13**.
- Kafka inbound adapter and its Compose service — **P-14**.
- Building/pushing the Docker image in CI — out of P-01's build+test gate.

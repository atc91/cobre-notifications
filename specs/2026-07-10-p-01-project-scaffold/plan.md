# P-01 · Project scaffold — Plan

The greenfield foundation for `cobre-notifications`: a bootable Spring Boot WebFlux
service, the hexagonal package skeleton for all three bounded contexts, local Docker
orchestration for Postgres, a working `/actuator/health`, and a CI gate.

**Scope decisions (confirmed):**
- **DB-deferred shell.** Dependencies are WebFlux + Actuator + Validation only. No
  `data-r2dbc` starter, no `spring.r2dbc` config, no DB health component in P-01.
  `docker-compose.yml` *stages* the Postgres service, but the app does not connect to it.
  All R2DBC/schema wiring lands in **P-02**. The service boots green with no running DB.
- **CI = build + test.** GitHub Actions on push/PR: JDK 25 + `./gradlew build`.

Stack (mirrors the `matchly/platform-api` sibling): Spring Boot 4.1.0, Java 25 LTS,
Gradle 9.6.0 (Kotlin DSL), Project Reactor, `postgres:18-alpine`, multi-stage Temurin
Docker image.

---

## Build & tooling (Gradle)

- [ ] Generate the Gradle wrapper at **9.6.0** (`gradlew`, `gradlew.bat`, `gradle/wrapper/`).
- [ ] `settings.gradle.kts` — `rootProject.name = "cobre-notifications"` (standalone
      project; **no** `includeBuild` — unlike the matchly monorepo, this service is
      self-contained and owns its `common/` code).
- [ ] `build.gradle.kts`:
  - Plugins: `java`, `org.springframework.boot` 4.1.0, `io.spring.dependency-management` 1.1.7.
  - `group = "com.cobre"`, `version = "0.0.1-SNAPSHOT"`.
  - Java toolchain `languageVersion = JavaLanguageVersion.of(25)`.
  - Dependencies: `spring-boot-starter-webflux`, `spring-boot-starter-actuator`,
    `spring-boot-starter-validation`; test: `spring-boot-starter-test`, `reactor-test`.
  - `tasks.withType<Test> { useJUnitPlatform() }`.
- [ ] Confirm `.gitignore` covers Gradle/build output (`build/`, `.gradle/`) alongside the
      existing IntelliJ ignores.

## Application shell (Backend)

- [ ] `src/main/java/com/cobre/notifications/NotificationsApplication.java` — a
      `@SpringBootApplication` class with `main` calling `SpringApplication.run(...)`.
- [ ] Verify the app boots on port 8080 with the `webflux` reactive stack (Netty), not MVC.

## Hexagonal package skeleton

Materialize the three-layer hexagon so the Dependency Rule is enforceable from day one.
Empty packages don't survive Git, so each is anchored with a `package-info.java` marker.

- [ ] `common/` — package for config, error mapping, observability (populated later).
- [ ] `delivery/` — `domain/{model,port/in,port/out}`, `application`, `adapter/{in,out}`
      marker packages.
- [ ] `subscription/` — same hexagon sub-structure.
- [ ] `query/` — same hexagon sub-structure (read + command surface; owns no data).
- [ ] Add a one-line `package-info.java` in each context root documenting its
      responsibility (from `architecture.md` §Ports & adapters catalog).

## Configuration

- [ ] `src/main/resources/application.yml` — base config: `spring.application.name`,
      `server.port: 8080`, Actuator exposure limited to `health` (and `info`).
- [ ] `src/main/resources/application-local.yml` — `local` profile placeholder (debug
      logging on `com.cobre.notifications`); no DB credentials yet (added in P-02).

## Infrastructure (Docker)

- [ ] `Dockerfile` — multi-stage Temurin build:
      `eclipse-temurin:25-jdk-alpine` build stage runs `./gradlew build -x test --no-daemon`;
      `eclipse-temurin:25-jre-alpine` runtime stage copies the boot jar, `EXPOSE 8080`,
      `ENTRYPOINT ["java","-jar","app.jar"]`.
- [ ] `docker-compose.yml` — a single `notifications-db` service on `postgres:18-alpine`,
      port `5432:5432`, `POSTGRES_DB/USER/PASSWORD` from env, `pg_isready` healthcheck.
      (App/Kafka services added in later phases; Postgres is staged now, not yet consumed.)
- [ ] `.env.example` — `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` sample values.

## Tests

Per `architecture.md` §Testing strategy, P-01 introduces no domain logic (→ no **Unit**
tests) and no cross-context journey (→ no **E2E**). It exercises only Spring wiring and the
health endpoint, both verified at the **Integration** layer. No Testcontainers — there is
no DB to stand up in this phase.

- [ ] **[Integration]** `contextLoads()` — the `@SpringBootTest` application context starts
      successfully with no DB present (proves the shell + config are wired and boot green).
- [ ] **[Integration]** `GET /actuator/health` returns HTTP 200 with body `{"status":"UP"}`,
      asserted via `WebTestClient` (proves Actuator health is exposed over WebFlux).

## CI

- [ ] `.github/workflows/ci.yml` — trigger on `push` and `pull_request`:
      `actions/checkout`, `actions/setup-java` (Temurin **25**), Gradle cache, then
      `./gradlew build` (compiles and runs the two integration tests).
- [ ] Confirm the workflow is green on the feature branch push.

## Smoke test & wrap-up

- [ ] Run the full validation checklist in `validations.md`.
- [ ] `./gradlew build` passes locally.
- [ ] `docker compose up notifications-db -d` starts Postgres and reports healthy.
- [ ] `curl http://localhost:8080/actuator/health` → `{"status":"UP"}` against the running app.
- [ ] `README.md` (or a short run section) documents build, run, and health-check commands.

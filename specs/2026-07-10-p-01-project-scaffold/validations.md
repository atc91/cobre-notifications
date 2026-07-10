# P-01 · Project scaffold — Validations

Each item is independently verifiable. Check only what you have actually observed.

## Build & structure

- [x] `./gradlew --version` reports Gradle **9.6.0**; the wrapper files
      (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`) are committed.
- [x] `build.gradle.kts` declares Spring Boot `4.1.0`, `group = "com.cobre"`, and a Java
      **25** toolchain; the classpath contains `webflux`, `actuator`, and `validation` and
      **not** `data-r2dbc`, `security`, or `spring-boot-starter-web`.
- [x] `settings.gradle.kts` sets `rootProject.name = "cobre-notifications"` with no
      `includeBuild`.
- [x] The package tree exists under `com.cobre.notifications`: `common`, and
      `delivery`/`subscription`/`query` each with `domain/{model,port/in,port/out}`,
      `application`, and `adapter/{in,out}` — each anchored by a committed `package-info.java`
      (22 markers total; verified all are git-tracked after fixing the `out/` ignore).
- [x] `.gitignore` excludes `build/` and `.gradle/`, and anchors the IntelliJ `out/` ignore
      to the repo root (`/out/`) so the hexagon's `adapter/out` and `port/out` packages are
      not swallowed.

## Runtime & health

- [x] `./gradlew build` succeeds from a clean checkout (compiles + tests pass).
- [x] `java -jar build/libs/*.jar` starts the app; logs show **Netty started on port 8080**
      (no Tomcat).
- [x] `curl http://localhost:8080/actuator/health` returns HTTP 200 and body
      `{"groups":["liveness","readiness"],"status":"UP"}` with no `db`/`r2dbc` component.

## Tests (one checkbox per required test)

- [x] **[Integration]** `contextLoads()` passes — the `@SpringBootTest` context starts with
      no database available.
- [x] **[Integration]** `GET /actuator/health` test passes — `WebTestClient` asserts HTTP
      200 and `$.status == "UP"`.

## Infrastructure

- [x] `docker build -t cobre-notifications .` completes both stages and produces a runnable
      image (`docker run -p 8080:8080 cobre-notifications` answers `/actuator/health` with
      HTTP 200 `{"status":"UP"}`).
- [x] `docker compose up notifications-db -d` starts `postgres:18-alpine`; the container
      reaches `healthy` per its `pg_isready` healthcheck (verified PostgreSQL 18.4 accepting
      connections as `notifications`).
- [x] `.env.example` lists `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`.

## CI & merge criteria

- [x] `.github/workflows/ci.yml` triggers on `push` and `pull_request`, sets up Temurin
      JDK 25, and runs `./gradlew build`.
- [ ] The CI workflow run on the `feature/p-01-project-scaffold` branch is **green**.
      _(Pending first push to GitHub.)_
- [ ] **Merge criteria:** all boxes above checked, CI green, no `data-r2dbc`/security/schema
      code introduced (those belong to later phases), and the PR reviewed and approved.

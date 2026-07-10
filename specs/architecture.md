# Architecture

_Always load. This service is backend-only; there is no frontend counterpart._

The case mandates **hexagonal architecture (ports & adapters)**, so this service organizes each bounded context as an explicit hexagon (`domain` / `application` / `adapter`) rather than by technical layer. The supporting conventions — reactive stack, exception→HTTP mapping, Flyway + R2DBC, three-layer testing, 12-factor config, Docker — are described in full below.

---

## Solution proposal at a glance

A single Spring Boot WebFlux service that **persists first and delivers later**. Platform events arrive (Kafka in the target design, a JSON seed loader today), are gated against the client's **subscription**, and land in Postgres as `PENDING` notifications. A **scheduler** then claims due rows, POSTs them over HTTPS to the client's webhook behind an **SSRF guard**, records every attempt, and either marks the row `DELIVERED` or schedules an exponential-backoff **retry** — falling through to a replayable `FAILED` dead-letter after `maxAttempts`. A **self-service REST API** reads and re-drives that same store, always scoped to the authenticated client. The database is the single source of truth; every side effect derives from a persisted row.

Three bounded contexts, each a hexagon, one deployable — but the seam between them is already drawn so `query` (API) and `delivery`+`subscription` (worker) can split into two services without touching domain code.

```
                                           cobre-notifications  (one deployable, 3 hexagons)
 ┌────────────────────┐                ┌────────────────────────────────────────────────────────────┐
 │  Cobre platform    │  events        │                                                            │
 │  services (accounts│ ─────────────▶ │  ┌──────────────┐   subscribed?   ┌────────────────────┐   │
 │  payments, …)      │  Kafka (target)│  │   INGEST     │ ──────────────▶ │  subscription       │   │
 └────────────────────┘  / JSON seed   │  │ IngestEvent  │ ◀───targetUrl── │  (SubscriptionPort) │   │
                         (today)        │  └──────┬───────┘                 └────────────────────┘   │
                                        │         │ persist PENDING                                  │
                                        │         ▼                                                  │
                                        │   ┌───────────────────────────────┐                       │
                                        │   │  PostgreSQL  (source of truth) │                       │
                                        │   │  notifications │ delivery_     │                       │
                                        │   │  (aggregate)   │ attempts      │                       │
                                        │   └──────┬─────────────────▲───────┘                       │
                                        │  claim due│ FOR UPDATE      │ record attempt               │
                                        │  SKIP LOCKED                │ + advance status             │
                                        │         ▼                   │                              │
                                        │   ┌──────────────┐   ┌──────┴───────┐  HTTPS POST          │   ┌──────────────┐
                                        │   │ DueDelivery  │──▶│ WebhookClient │────────────────────────▶│ client        │
                                        │   │ Scheduler    │   │ (SSRF guard)  │  Idempotency-Key     │   │ webhook URL   │
                                        │   └──────────────┘   └──────────────┘                      │   └──────────────┘
                                        │      retry w/ backoff → RETRYING → FAILED (dead-letter)     │
                                        │                                                            │
                                        │   ┌───────────────────────────────────────┐               │   ┌──────────────┐
                                        │   │  Self-service REST API (query hexagon) │◀──────list/───────│ client        │
                                        │   │  GET  /notification_events[/{id}]      │      inspect  │   │ developer     │
                                        │   │  POST /notification_events/{id}/replay │──────replay──────▶│ (client-scoped)│
                                        │   └───────────────────────────────────────┘               │   └──────────────┘
                                        └────────────────────────────────────────────────────────────┘
                                            Actuator / Micrometer ─▶ metrics · structured logs · alerts ─▶ monitoring team
```

**Status lifecycle:** `PENDING → DELIVERING → DELIVERED` on success; `→ RETRYING → DELIVERING` while attempts remain; `→ FAILED` (replayable dead-letter) once exhausted. Detailed in [Domain model](#domain-model) and [Delivery flow & the dual-write problem](#delivery-flow--the-dual-write-problem).

---

## Assumptions (open — flag to change)

| # | Assumption | Why | Change cost |
|---|---|---|---|
| A1 | **PostgreSQL** is the store | Strong for the queryable audit trail and `SELECT ... FOR UPDATE` claim of due work | Low (port-isolated) |
| A2 | **Kafka** is the target broker | Best fit for Cobre's "event-driven microservices" narrative; SQS is the managed alternative | Low (inbound adapter only) |
| A3 | Public API auth = **OAuth2 client-credentials / API key per client** | Standard for machine-to-machine self-service APIs | Medium |
| A4 | Retry policy: **5 attempts, base 30s, factor 2, jitter, cap 1h** | Balances promptness vs. hammering a down endpoint | Trivial (config) |

## Repository

One deployable service, one Gradle project. Packages enforce the hexagon and the bounded-context boundaries, so the **modular monolith is split-ready** (see [Deployment topology](#deployment-topology)).

```
cobre-notifications/
├── docker-compose.yml            ← Postgres (+ Kafka, later)
├── Dockerfile
├── specs/
│   └── notification_events.json  ← seed data for the implementation
└── src/
    ├── main/java/com/cobre/notifications/
    │   ├── NotificationsApplication.java
    │   ├── delivery/             ← bounded context: deliver events to webhooks
    │   ├── subscription/         ← bounded context: is this client subscribed?
    │   ├── query/                ← bounded context: self-service read + replay
    │   └── common/               ← config, security, observability, error mapping
    ├── main/flyway/              ← V<n>__<desc>.sql (applied via JDBC at test time)
    └── main/resources/
        └── application*.yml
```

## The hexagon

Each bounded context is a hexagon with the same three-layer internal shape:

```
com.cobre.notifications.<context>/
├── domain/
│   ├── model/        ← entities, value objects, domain rules (pure Java, no Spring)
│   └── port/
│       ├── in/       ← inbound (driving) ports — use-case interfaces the context offers
│       └── out/      ← outbound (driven) ports — interfaces the context needs
├── application/      ← use-case implementations; compose Mono/Flux; own @Transactional
└── adapter/
    ├── in/           ← driving adapters: REST controller, Kafka consumer, scheduler, seeder
    └── out/          ← driven adapters: R2DBC repository, WebClient webhook client
```

### The Dependency Rule (the one rule)

**Dependencies point inward. `domain` and `application` never import from `adapter`, Spring web, R2DBC, or Kafka.** The context *defines* its outbound port interfaces; adapters implement them. At compile time every arrow points adapter → application → domain. This is what keeps the core unit-testable with zero infrastructure and lets us swap Postgres, Kafka, or the HTTP client without touching business logic.

| Layer | May depend on | Must never import |
|---|---|---|
| `domain` | nothing (pure Java + Reactor types) | Spring, R2DBC, Kafka, web, other contexts |
| `application` | its own `domain` | any `adapter`, HTTP/DB/broker types |
| `adapter.in` | its `application` inbound ports | `adapter.out` directly |
| `adapter.out` | its `application` outbound ports | `adapter.in` |
| `common` | framework config only | any context's `domain`/`application` |

## Ports & adapters catalog

The whole system is four inbound ports and three outbound ports. Everything the case asks for maps onto them.

### Inbound (driving) ports — what the system offers

| Port | Driven by (adapter) | Responsibility |
|---|---|---|
| `IngestEventUseCase` | Kafka consumer (target) · JSON seed loader (impl) | Accept a platform event, confirm subscription, persist a `PENDING` notification |
| `DeliverNotificationUseCase` | `DueDeliveryScheduler` (`@Scheduled`) | Attempt delivery of one due notification; record the attempt; advance status |
| `QueryNotificationsUseCase` | `NotificationEventController` (REST) | List / fetch a client's notifications (client-scoped) |
| `ReplayNotificationUseCase` | `NotificationEventController` (REST) | Re-enqueue a definitively `FAILED` notification for a fresh delivery |

### Outbound (driven) ports — what the system needs

| Port | Implemented by (adapter) | Responsibility |
|---|---|---|
| `NotificationStorePort` | `R2dbcNotificationRepository` | Persist / find notifications + delivery attempts; claim due work |
| `SubscriptionPort` | `R2dbcSubscriptionRepository` | Answer "is client C subscribed to event E, and to which URL?" |
| `WebhookClientPort` | `WebClientWebhookAdapter` | HTTPS POST the payload to the client URL; return the outcome |

`ClockPort` (a trivial outbound port) is injected wherever backoff timing is computed, so retry timing is deterministic in tests.

## Domain model

```
Notification (aggregate root)
  id            (= platform event_id, idempotency key)
  clientId
  eventType
  content / payload
  targetUrl                 ← resolved from the subscription at ingest
  deliveryStatus            ← PENDING | DELIVERING | RETRYING | DELIVERED | FAILED
  attempts                  ← count
  nextRetryAt               ← drives the due-work query
  claimedAt                 ← lease timestamp; lets the reaper reclaim stuck DELIVERING rows
  lastError
  createdAt / deliveredAt / updatedAt

DeliveryAttempt (child, append-only audit)
  id, notificationId, attemptNo, attemptedAt, httpStatus, error, durationMs

Subscription
  id, clientId, eventType (or '*'), targetUrl, secret, active
```

**Status lifecycle** (the JSON's `completed`/`failed` map onto the terminal states `DELIVERED`/`FAILED`):

```
PENDING ──▶ DELIVERING ──success──▶ DELIVERED   (= "completed")
                │
                └──failure──▶ RETRYING ──(attempts < max)──▶ DELIVERING
                                   │
                                   └──(attempts = max)──▶ FAILED  (= "failed", replayable)
```

**Status columns** are `VARCHAR` + `CHECK` constraint, not PG `ENUM` — no R2DBC codec wiring, and new statuses are a plain `ALTER`.

## Data ownership

"Database per service" conflates two separate decisions. We keep them separate: **logical table ownership** (enforced from day one) versus **physical database instances** (a later, independent choice — *not* automatic on a code split).

**One PostgreSQL instance, three contexts, strict table ownership.** The rule that makes the boundary real:

> A context may only touch another context's tables **through that context's port** — never via a direct SQL join across the boundary.

Enforced in the monolith, this rule survives a later split unchanged.

Only two of the three contexts own data. `query` has none of its own — it is a **read + command surface** over `delivery`'s aggregate:

| Context | Owns | Nature |
|---|---|---|
| `subscription` | `subscriptions` | data-owning context |
| `delivery` | `notifications`, `delivery_attempts` (the `Notification` aggregate) | data-owning context |
| `query` | — | read + command surface over `delivery` |

**Evolution when `query` splits into its own service.** It then needs to read notifications it does not own. In order of preference:

1. **Shared database, read-only access** — `notifications-api` reads the notification tables read-only. Simple, strongly consistent; the recommended default.
2. **CQRS read model** — `delivery` publishes `NotificationStatusChanged` events; `query` maintains its own read-optimized store. Adopt *only if* read load justifies it; trades strong consistency for independent scaling + isolation.
3. **Synchronous internal API** — avoid: couples availability and is chatty for list endpoints.

`subscription` could take its own database instance one day (it is independent of the notification lifecycle), but there is no pressure to until it needs one. Splitting the *deployable* and splitting the *database* are two levers, pulled for different reasons at different times.

## Deployment topology

**Now:** one deployable (`cobre-notifications`) containing all three contexts. The REST API and the delivery worker (scheduler + consumer) run in the same process.

**Split-ready:** the seam is already drawn. When delivery volume and query volume need to scale independently, lift:

- `query/` → **notifications-api** (stateless, scales with client traffic)
- `delivery/` + `subscription/` → **notifications-worker** (scales with event volume)

Both talk to the same Postgres and Kafka; no code in `domain`/`application` changes because they only know ports. This is the scalability answer to **Task 1**.

## Delivery flow & the dual-write problem

The flow "event arrives on Kafka → persist → deliver later" contains **two distinct dual-write points**. Naming them separately is what makes them tractable.

### Point 1 — Ingest (Kafka → DB): a single write, not a dual write

We **persist first and deliver later**; we do *not* call the webhook during consumption. Consumption therefore performs a single DB write, so there is nothing to keep in sync. What remains is at-least-once hygiene:

- **Order:** process (DB insert) → *then* commit the Kafka offset. Never commit the offset first. A failed insert means an uncommitted offset, so Kafka redelivers.
- **Idempotency:** the notification id **is** the `event_id` with a unique constraint, so redelivery is `INSERT ... ON CONFLICT DO NOTHING` — a harmless no-op.

The offset is a checkpoint, not a second copy of business data. Delivering off the *persisted row* (never off the Kafka message) is what makes the database the single source of truth — the "inbox" half of inbox/outbox.

### Point 2 — Delivery (DB row ↔ HTTP webhook): the unavoidable dual write

The external webhook and the database **cannot share a transaction**. Exactly-once across an HTTP boundary is impossible; the choice is at-most-once (risk silent loss) or at-least-once (risk duplicates). For notifications we choose **at-least-once + client idempotency**. We never mark `DELIVERED` optimistically. Ordering:

```
1. TX: claim a due row (SELECT … FOR UPDATE SKIP LOCKED),
       set status = DELIVERING, claimedAt = now, attempts += 1     → COMMIT
2.     HTTP POST to webhook   (header  Idempotency-Key: <event_id>)
3. TX: INSERT delivery_attempt(outcome),
       UPDATE notification → DELIVERED | RETRYING(nextRetryAt) | FAILED → COMMIT
```

The only crash window is **between step 2 and step 3**: the call may have landed but the outcome wasn't recorded. A **reaper** resolves it — any row stuck in `DELIVERING` past a lease timeout (`claimedAt`) is reset to `RETRYING` and re-attempted.

| Crash point | Call made? | DB says | Recovery | Client impact |
|---|---|---|---|---|
| After claim (1), before POST | No | `DELIVERING` (stale) | reaper → `RETRYING` → retry | none |
| After POST (2), before record (3) | Maybe/yes | `DELIVERING` (stale) | reaper → `RETRYING` → retry | **possible duplicate** |
| During record (3) | Yes | TX rolls back → `DELIVERING` | reaper → `RETRYING` → retry | possible duplicate |
| Normal success | Yes | `DELIVERED` | — | none |
| Normal failure | Yes | `RETRYING` / `FAILED` | scheduler retries | none |

### The guarantee

- **Sent ⇒ reflected:** the row *converges* to `DELIVERED`. A crash after a successful send is retried by the reaper; the client dedupes the duplicate via the `Idempotency-Key`; the row ends terminal. A truly-delivered notification is never left silently stuck.
- **Failed ⇒ reflected:** every attempt writes an append-only `delivery_attempt` row and advances status to `RETRYING`, or to `FAILED` after `maxAttempts` (the replayable dead-letter).
- **No false success, no silent loss:** we never mark `DELIVERED` without a real 2xx, and the worst case is a *duplicate*, never a *loss*.

### Where the outbox pattern belongs

The **transactional outbox** solves "atomically update my DB *and* publish to Kafka" — which is a **producer-side** concern (the platform services emitting events). Our consumer side makes an HTTP call, not a Kafka publish, so it uses the **inbox + claim-and-record** pattern above instead: same philosophy (DB is the source of truth, side effects derive from persisted state), different mechanism.

## Reliability & retry design

The **efficient retry strategy** is DB-backed and durable across restarts:

- Each failed attempt increments `attempts`, sets `deliveryStatus = RETRYING`, and computes `nextRetryAt = now + min(base * 2^attempts + jitter, cap)` (A4).
- `DueDeliveryScheduler` polls for work where `status IN (PENDING, RETRYING) AND nextRetryAt <= now`, claiming rows with `SELECT ... FOR UPDATE SKIP LOCKED` so multiple worker instances never double-deliver.
- After `maxAttempts`, the notification becomes `FAILED` — a **dead-letter** terminal state that is exactly what `POST /replay` acts on.
- **Idempotency:** the notification id is the platform `event_id`; re-ingesting the same event is a no-op, so at-least-once broker delivery is safe.

Reactive rules are hard constraints: controllers return `Mono<ResponseEntity<T>>`/`Flux<T>`, services compose `Mono`/`Flux`, `.block()` is forbidden, repositories extend `R2dbcRepository`.

## Self-service API contract

| Method / path | Use case | Notes |
|---|---|---|
| `GET /notification_events` | `QueryNotificationsUseCase` | Filter by `created_from`/`created_to` and `delivery_status`; **always** scoped to the authenticated client; paginated |
| `GET /notification_events/{id}` | `QueryNotificationsUseCase` | 404 if not found **or not owned by the caller** (no ownership leak via 403 vs 404) |
| `POST /notification_events/{id}/replay` | `ReplayNotificationUseCase` | 409 unless status is `FAILED`; resets to `PENDING`, `nextRetryAt = now` |

Exception→HTTP mapping lives in a single `GlobalExceptionHandler` (`NotFoundException`→404, `ConflictException`→409, `BadRequestException`→400, `UnauthorizedException`→401, `ForbiddenException`→403). Services throw; controllers own `ResponseEntity`.

## Security (Task 3 — OWASP Top 10)

Three risks that specifically threaten *this* public, webhook-calling, multi-tenant API, each with a concrete mitigation:

| OWASP risk | Why it applies here | Mitigation |
|---|---|---|
| **A01 Broken Access Control** | Any client hitting `GET /{id}` or `/replay` could try to read/act on another client's notification (the core "notifications belong to that client" mandate) | Enforce client scoping at the **service layer**, not the controller: every query is filtered by the authenticated `clientId`; ownership is checked before replay; unauthorized access returns **404** (never reveals existence). Verified in integration tests. |
| **A10 Server-Side Request Forgery (SSRF)** | The service makes outbound HTTPS calls to **client-supplied URLs** — a classic SSRF vector into Cobre's internal network / cloud metadata endpoint | `WebhookClientPort` adapter enforces **HTTPS-only**, resolves the host and **rejects private/loopback/link-local/metadata IP ranges** (with DNS-rebinding re-check after resolution), applies strict connect/read timeouts, and disables redirects. Target URLs are validated at subscription time too. |
| **A07 Identification & Authentication Failures** | A public, internet-facing API is a brute-force / credential-stuffing target | OAuth2 **client-credentials** (or per-client API keys) required on every request (A3); short-lived tokens; per-client **rate limiting**; audit logging of auth failures feeds the monitoring pipeline. |

Supporting measures (worth a mention in the panel): **A08 Data Integrity** — sign webhook payloads with a per-subscription HMAC secret so clients can verify authenticity; **A03 Injection** — parameterized R2DBC queries only; **A09 Logging/Monitoring Failures** — addressed directly by the observability section below.

## Observability (near real-time)

The monitoring team must detect deviations and answer complaints before the client escalates. Built on Spring Boot Actuator + Micrometer:

- **Metrics** (Prometheus scrape → Grafana): delivery success/failure counters by `eventType` and `clientId`, attempt-count histogram, delivery-latency histogram, **retry backlog gauge** (rows due but not yet delivered), and `FAILED`/dead-letter rate.
- **Structured JSON logs** with a correlation id = `event_id` propagated through ingest → attempt → outcome, so one complaint resolves to one log query.
- **Tracing:** OpenTelemetry spans across consumer → use case → webhook call.
- **Alerts:** failure-rate spike, backlog growth, and any dead-lettering trigger monitoring-team alerts.
- **Health:** `/actuator/health` (liveness/readiness) for Kubernetes.

## Infrastructure

| Concern | Choice |
|---|---|
| Containerization | Docker — one image |
| Local orchestration | Docker Compose (`docker compose up` → Postgres, later Kafka) |
| Config | Environment variables (12-factor); a `local` profile carries hardcoded localhost credentials + SQL debug logging |
| Cloud target | Kubernetes-compatible; API tier stateless; worker horizontally scalable via `SKIP LOCKED` |

**Database conventions:** Flyway scripts in `src/main/flyway/`, applied via JDBC **at test time only**; the production container uses R2DBC exclusively.

## Testing strategy

Three layers, one decision rule — a question answered at a lower layer is never re-asked higher up.

| Layer | Question answered | Tools |
|---|---|---|
| **Unit** | Does the domain logic work in isolation? | JUnit 5, no Spring — retry/backoff math, status transitions, SSRF URL validation, subscription rules |
| **Integration** | Does the port contract hold against real infra? | `@SpringBootTest` + Testcontainers (Postgres) + `StepVerifier` — every endpoint (happy + 404/409/401/403), `SKIP LOCKED` claim under concurrency, real webhook call against a stub server |
| **E2E / walkthrough** | Can an event travel ingest → deliver → retry → replay end to end? | Scripted integration walkthrough seeded from `notification_events.json` |

The domain hexagon is the big win here: retry policy, status lifecycle, and SSRF guards are **pure unit tests** with no container, because they depend only on ports.

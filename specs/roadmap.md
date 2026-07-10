# Roadmap

Each phase is a single shippable slice — one PR, a few hours of focused work. Phases are ordered so the **domain hexagon is testable before any infrastructure exists**, and the three required endpoints come online only after delivery works end to end.

**Legend:** `✓` done · `→` in progress · `·` not started

The case-critical path is **P-01 → P-11** (delivery pipeline + the three mandated endpoints). P-12+ are hardening and the target-design broker. Kafka is deliberately late so the case is finishable on the JSON seed alone.

---

## Foundation

| Status | Phase | Description |
|---|---|---|
| ✓ | P-01 · Project scaffold | Gradle + Spring Boot WebFlux shell, hexagonal package skeleton (`delivery`/`subscription`/`query`/`common`), Docker Compose (Postgres), `/actuator/health`, CI. |
| ✓ | P-02 · Database baseline | Flyway migrations for `notifications`, `delivery_attempts`, `subscriptions`; R2DBC wiring; one Testcontainers integration test proving the schema loads. |

## Domain core (no infrastructure)

| Status | Phase | Description |
|---|---|---|
| ✓ | P-03 · Domain model + ports | `Notification` aggregate, `DeliveryAttempt`, `DeliveryStatus`, `RetryPolicy` value object, and all inbound/outbound port interfaces. Pure JUnit unit tests for status transitions and backoff math. No Spring. |

## Ingestion

| Status | Phase | Description |
|---|---|---|
| ✓ | P-04 · JSON seed loader | Inbound adapter (`ApplicationRunner`) reads `notification_events.json` → `IngestEventUseCase` → persists notifications as `PENDING`. `NotificationStorePort` gets its R2DBC adapter. |
| ✓ | P-05 · Subscription gate | `SubscriptionPort` + R2DBC adapter; ingest confirms the client is subscribed and resolves `targetUrl` before persisting. Events without a matching subscription are rejected/skipped (no cross-client delivery). |

## Delivery

| Status | Phase | Description |
|---|---|---|
| ✓ | P-06 · Webhook client adapter | `WebhookClientPort` → `WebClientWebhookAdapter`: HTTPS POST with timeouts, no redirects, and the **SSRF guard** (https-only, block private/loopback/metadata ranges). Unit-tested URL validation. |
| ✓ | P-07 · Delivery use case + scheduler | `DeliverNotificationUseCase` + `DueDeliveryScheduler` claims due rows (`FOR UPDATE SKIP LOCKED`), attempts delivery, records a `DeliveryAttempt`, sets `DELIVERED` or `RETRYING`. |
| ✓ | P-08 · Retry strategy | Exponential backoff + jitter → `nextRetryAt`; cap at `maxAttempts` → `FAILED` (dead-letter). Integration test drives a flaky stub server through retry → success and retry → exhaustion. |

## Self-service API (the three mandated endpoints)

| Status | Phase | Description |
|---|---|---|
| · | P-09 · `GET /notification_events` | List, client-scoped, filter by creation date range + `delivery_status`, paginated. |
| · | P-10 · `GET /notification_events/{id}` | Single event details; 404 when not found **or not owned** (no existence leak). |
| · | P-11 · `POST /{id}/replay` | Re-enqueue a `FAILED` notification (409 otherwise); resets to `PENDING`, `nextRetryAt = now`. Closes the delivery loop with the retry engine. |

## Hardening

| Status | Phase | Description |
|---|---|---|
| · | P-12 · Security | Authentication on the public API (OAuth2 client-credentials / API key), service-layer access control verified in integration tests, per-client rate limiting. Covers OWASP A01/A07. |
| · | P-13 · Observability | Micrometer metrics (success/failure counters, latency + attempt histograms, backlog gauge), structured JSON logs with `event_id` correlation, Prometheus endpoint, alert definitions. |

## Target design (optional / if time)

| Status | Phase | Description |
|---|---|---|
| · | P-14 · Kafka inbound adapter | Add `KafkaEventConsumer` → the same `IngestEventUseCase`, proving the JSON seeder and the broker are interchangeable driving adapters. Compose gains a Kafka service. |
| · | P-15 · Walkthrough + deliverables | Scripted end-to-end run (ingest → deliver → retry → replay) seeded from the JSON; README; **AI-usage documentation** (prompts/screenshots per the case); C4 diagrams for the panel. |

---

## Post-MVP / discussion points for the panel

- **Physical service split** — lift `query/` into `notifications-api` and `delivery/`+`subscription/` into `notifications-worker` (the seam already exists).
- **Transactional outbox** on the producing services so event publication and business writes commit atomically.
- **HMAC payload signing** (per-subscription secret) so clients verify webhook authenticity — OWASP A08.
- **Dead-letter topic** + automated re-drive tooling for the monitoring team.
- **mTLS** to client webhooks where the client supports it.
- Additional delivery channels (email, SMS, push) as new outbound adapters behind a shared `ChannelPort`.

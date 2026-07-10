# P-05 · Subscription gate — Requirements

## Scope

P-05 makes ingestion **subscription-gated**. Before a notification is persisted, ingest
confirms the owning client has an **active** subscription covering the event's type and
resolves the webhook `targetUrl` from that subscription; only then is the `PENDING`
notification written (now with a real `targetUrl` instead of the P-04 `null`). An event with
**no** matching active subscription is **skipped** — not persisted, not delivered — which is
the concrete enforcement of "a client only ever receives events that belong to them" (no
cross-client leakage).

To give the gate data to resolve against, this phase also delivers the `subscription`
context's first working slice: a JSON seed loader that populates the `subscriptions` table on
startup from a bundled `subscriptions.json`, ingested through the subscription hexagon's own
inbound port and R2DBC adapter (symmetric with P-04's event seeding).

This phase **does not**: validate or SSRF-guard the `targetUrl` (P-06 — it is only read and
stored here); call any webhook or attempt delivery (P-06+); claim due work or record delivery
attempts (P-07/P-08); add any REST endpoint or subscription-management API (subscriptions are
seed/admin-provisioned); add or alter a Flyway migration (the `subscriptions` table exists from
P-02/V1); or introduce Kafka (P-14).

## Functional requirements

1. **Subscription resolution port (read side).** `R2dbcSubscriptionRepository` (in the
   `subscription` context, which owns the `subscriptions` table) implements
   `delivery.domain.port.out.SubscriptionPort` using `DatabaseClient`. `resolve(clientId,
   eventType)` returns a `SubscriptionLookup(targetUrl, secret)` for the **active** subscription
   covering that client + event type, or an **empty** `Mono` when none matches.
2. **Exact-over-wildcard, active, client-scoped.** Resolution honors the same rule as
   `Subscription.matches` (P-03): the subscription must be `active` and belong to the same
   `clientId`, and its `event_type` must equal the event type **or** be the wildcard `'*'`. When
   both an exact and a wildcard subscription exist for the client, the **exact** match is chosen.
3. **Gated ingest.** `IngestEventService` resolves the subscription first. On a match it builds
   a `PENDING` `Notification` with `targetUrl` set to the resolved URL and persists it via
   `NotificationStorePort.save`. On no match it **completes without persisting** and logs a
   concise skip line (`clientId`/`eventType`); no notification row is created and no error is
   raised (safe to commit a Kafka offset later — an unsubscribed event is expected, not a
   failure).
4. **No cross-client leakage.** An event whose `clientId` has no active subscription for its
   type is never persisted and therefore never delivered. This is verified end to end: the seed
   run persists 9 of the 10 events and skips the one uncovered event (EVT009).
5. **Subscription write port (seed side).** The `subscription` context exposes an inbound
   `RegisterSubscriptionUseCase.register(SubscriptionRegistration)` implemented by
   `RegisterSubscriptionService`, backed by an outbound `SubscriptionStorePort.save(...)` whose
   R2DBC adapter issues `INSERT ... ON CONFLICT (client_id, event_type) DO NOTHING` (id /
   created_at fall to DB defaults). Registration is idempotent — re-seeding adds no rows.
6. **JSON subscription seed adapter.** `SubscriptionSeedLoader` (an `ApplicationRunner`) reads
   the seed resource, maps each JSON entry to a `SubscriptionRegistration` (`client_id →
   clientId`, `event_type → eventType`, `target_url → targetUrl`, `secret`, `active` defaulting
   `true`), and registers each via `RegisterSubscriptionUseCase`, composed reactively without
   `.block()`. A malformed individual entry is logged and skipped, not fatal.
7. **Seed ordering.** The subscription seed loader runs **before** the event seed loader
   (`@Order`), so the gate has subscription data before any event is evaluated against it.
8. **Configurable activation.** The subscription loader is gated by
   `notifications.subscription-seed.enabled` (default `true`); the test profile sets it `false`
   so `@SpringBootTest` contexts do not auto-seed. The seed location is configurable via
   `notifications.subscription-seed.location` (default `classpath:subscriptions.json`).
9. **Bundled resource.** `subscriptions.json` ships on the classpath (`src/main/resources/`) so
   the packaged jar / Docker image is self-contained.

## Non-functional requirements

- **Reactive purity.** No `.block()` anywhere; `resolve`/`save` return reactive types, the
  service composes `Mono`/`Flux`, and the loader drives registration reactively. (Architecture
  hard rule.)
- **Dependency Rule / cross-context seam.** `delivery/application` and `delivery/domain` import
  no `subscription` types — `IngestEventService` depends only on `delivery`'s own
  `SubscriptionPort`. `subscription/application` and `subscription/domain` import no `adapter`,
  web, R2DBC, or Kafka types. Only `subscription/adapter/out` references `delivery`'s
  `SubscriptionPort` / `SubscriptionLookup` — the deliberate, already-scaffolded seam by which
  the subscription context implements the port delivery needs while owning the `subscriptions`
  table.
- **Parameterized queries only.** All subscription SQL is parameterized R2DBC (`:clientId`,
  `:eventType`) — no string concatenation (OWASP A03).

## Out of scope

- `targetUrl` SSRF validation / https-only enforcement and the webhook client (P-06).
- Any actual HTTP delivery, due-work claiming, delivery attempts, retry/backoff (P-06–P-08).
- REST endpoints, client scoping of the read API, and a subscription-management API
  (P-09–P-12; subscriptions here are seed/admin-provisioned only).
- HMAC payload signing with the per-subscription `secret` (the column is resolved and carried,
  but signing is a later phase).
- Any new or altered database schema / Flyway migration.
- Kafka inbound adapter (P-14).

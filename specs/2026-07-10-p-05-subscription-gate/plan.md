# P-05 · Subscription gate — Plan

Tasks grouped by the layers this phase actually touches. P-05 makes ingestion
**subscription-gated**: before persisting a notification, ingest confirms the client has an
active subscription for the event type and resolves the webhook `targetUrl` from it. Events
with no matching subscription are **skipped** (no cross-client leakage). It builds on P-04
(ingest use case + `delivery` store adapter) and P-03 (the `SubscriptionPort` /
`SubscriptionLookup` scaffolding + the `Subscription.matches` rule).

This phase also gives the `subscription` context its **first working slice**: a JSON seed
loader that populates the `subscriptions` table on startup, symmetric with P-04's event seed
loader. Subscription rows come from a bundled `subscriptions.json`, ingested through the
subscription hexagon's own inbound port.

Base package: `com.cobre.notifications`. Work spans the `subscription` context (new adapter +
application + write ports + seed loader) and one wiring change in the `delivery` context
(`IngestEventService`).

**Design note — the cross-context seam (already scaffolded).** `SubscriptionPort` lives in
`delivery/domain/port/out/` and returns the delivery-owned `SubscriptionLookup`; the
`delivery` core never imports the `subscription` context. Its implementation
(`R2dbcSubscriptionRepository`) lives in the `subscription` context, which owns the
`subscriptions` table — so `delivery` reaches subscription data only through the port. No SSRF
/ URL validation here (P-06); no HTTP delivery (P-06+); no new Flyway migration (the
`subscriptions` table exists from P-02/V1).

## Database

- [ ] **No schema change.** The `subscriptions` table (V1) already has every column P-05
      needs (`client_id`, `event_type`, `target_url`, `secret`, `active`, `created_at`, and the
      `UNIQUE (client_id, event_type)` constraint). P-05 only reads and seeds rows — no new
      migration.

## Backend — subscription context: read side (the gate)

- [ ] `R2dbcSubscriptionRepository` (`subscription/adapter/out/`) implements
      `delivery.domain.port.out.SubscriptionPort` over the `subscriptions` table, built on
      `DatabaseClient` (raw SQL). Annotated `@Repository`.
  - `resolve(String clientId, String eventType)` — resolves the active subscription covering
    this client + event type, preferring an **exact** `event_type` match over the `'*'`
    wildcard, and returns `Mono<SubscriptionLookup>(target_url, secret)`; **empty** when the
    client has no active matching subscription. SQL along the lines of:
    `SELECT target_url, secret FROM subscriptions WHERE client_id = :clientId AND active = true
    AND event_type IN (:eventType, '*') ORDER BY (event_type = :eventType) DESC LIMIT 1`.
    This is the adapter realization of the `Subscription.matches(...)` rule (unit-tested in
    P-03); the SQL enforces the same active + client-scoped + exact/wildcard semantics.

## Backend — subscription context: write side (seed persistence)

- [ ] `SubscriptionRegistration` (`subscription/domain/model/`) — a small domain record
      carrying the fields needed to register a subscription (`clientId`, `eventType`,
      `targetUrl`, `secret`, `active`). Pure Java; `id`/`createdAt` are DB-assigned on insert.
- [ ] `RegisterSubscriptionUseCase` (`subscription/domain/port/in/`) — inbound port:
      `Mono<Void> register(SubscriptionRegistration reg)`. Idempotent by contract.
- [ ] `SubscriptionStorePort` (`subscription/domain/port/out/`) — outbound port the
      subscription context needs to persist rows: `Mono<Void> save(SubscriptionRegistration reg)`.
- [ ] `RegisterSubscriptionService implements RegisterSubscriptionUseCase`
      (`subscription/application/`) — delegates to `SubscriptionStorePort.save`, returning
      `Mono<Void>`. Depends only on its own `domain` (never an adapter). Annotated `@Service`.
- [ ] `R2dbcSubscriptionRepository` **also** implements `SubscriptionStorePort` (same adapter,
      same table): `save` issues `INSERT INTO subscriptions (client_id, event_type, target_url,
      secret, active) VALUES (...) ON CONFLICT (client_id, event_type) DO NOTHING` (`id` /
      `created_at` fall to DB defaults). Idempotent re-seed — a duplicate `(client_id,
      event_type)` is a harmless no-op, so restarts add no rows.

## Backend — delivery context: wire the gate into ingest

- [ ] `IngestEventService` (`delivery/application/`) — inject `SubscriptionPort` alongside the
      existing `NotificationStorePort` + `ClockPort`. Rework `ingest(PlatformEvent)` to:
  1. `subscriptions.resolve(event.clientId(), event.eventType())`,
  2. on a resolved `SubscriptionLookup` → `store.save(Notification.pending(..., lookup.targetUrl(),
     clock.now()))` (the resolved `targetUrl`, **no longer `null`**),
  3. on **empty** (no active subscription) → `switchIfEmpty(...)`: log a concise skip line
     (`clientId`/`eventType`) and complete **without persisting** — no cross-client leakage,
     no error signalled.
  - Reactive-purity note for the implementer: `store.save` returns a **non-empty**
    `Mono<Notification>`, so `switchIfEmpty` fires only on the genuine no-subscription case,
    never on a successful save. Return `Mono<Void>` via `.then()`. No `.block()`.

## Backend — subscription context: JSON seed loader (inbound adapter)

- [ ] `SubscriptionSeedLoader implements ApplicationRunner` (`subscription/adapter/in/`) — on
      startup reads the seed resource, maps each entry to a `SubscriptionRegistration`, and
      calls `RegisterSubscriptionUseCase.register(...)` for each, composed reactively
      (`Flux.fromIterable(...).concatMap(...)`, no `.block()`). Logs a start/summary line
      (count registered). Gated by `@ConditionalOnProperty(name =
      "notifications.subscription-seed.enabled", havingValue = "true", matchIfMissing = true)`.
      Annotated with `@Order` so it runs **before** `EventSeedLoader` — subscriptions must exist
      before events are gated against them.
- [ ] `SubscriptionSeedEntry` DTO + `SubscriptionSeedParser` (`subscription/adapter/in/`) — a
      Jackson-bound view of `subscriptions.json` (`client_id`, `event_type`, `target_url`,
      `secret`, `active`) mapped to `SubscriptionRegistration` (`active` defaults `true` when
      absent). A malformed individual entry is logged and skipped, not fatal.

## Configuration & wiring (`src/main/resources/`)

- [ ] Add `src/main/resources/subscriptions.json` — the bundled subscription seed. Coverage
      (deliberately leaves one event uncovered to prove the gate skips it):
  - `CLIENT001` → `event_type: "*"` (covers EVT001/002/007/010)
  - `CLIENT002` → `event_type: "*"` (covers EVT003/004/008)
  - `CLIENT003` → `event_type: "credit_refund"` (covers EVT005)
  - `CLIENT003` → `event_type: "debit_transfer"` (covers EVT006)
  - → EVT009 (`CLIENT003` / `credit_cashback`) has **no** subscription and is skipped.
    All `target_url`s are `https://...`; `active: true`.
- [ ] `application.yml` — add `notifications.subscription-seed.enabled: true` and
      `notifications.subscription-seed.location: classpath:subscriptions.json` (env-overridable,
      12-factor), alongside the existing event-seed keys.
- [ ] `src/test/resources/application.yml` — set `notifications.subscription-seed.enabled:
      false` so `@SpringBootTest` contexts do not auto-seed subscriptions; the loader / gate
      integration tests opt back in explicitly.

## Tests

- [ ] **[Unit]** `IngestEventServiceTest` (update) — with a mocked `SubscriptionPort`,
      `NotificationStorePort`, and fixed `ClockPort`:
  - *subscribed*: `resolve` returns a `SubscriptionLookup`; `save` is invoked exactly once with
    a `PENDING` notification whose `targetUrl` equals the lookup's URL; the `Mono` completes.
  - *not subscribed*: `resolve` returns empty; `store.save` is **never** invoked; the `Mono`
    still completes (skip, not error).
- [ ] **[Unit]** `SubscriptionSeedFileMapperTest` — parsing `subscriptions.json` yields the 4
      expected `SubscriptionRegistration`s with correct field mapping (`client_id → clientId`,
      `target_url → targetUrl`, wildcard `"*"` preserved) and `active` defaulting to `true`.
- [ ] **[Integration]** `R2dbcSubscriptionRepositoryIT` (`@SpringBootTest` + Testcontainers
      Postgres + `StepVerifier`) — pins the SQL contract of the gate against real Postgres:
  - exact `event_type` match resolves the row's `target_url` + `secret`;
  - a `'*'` wildcard subscription resolves for any event type of that client;
  - when both an exact and a wildcard row exist, the **exact** one wins;
  - an `active = false` row resolves **empty**;
  - a different `client_id` resolves **empty** (client scoping — no cross-client leak);
  - an unknown event type with no wildcard resolves **empty**;
  - `save` is idempotent on `(client_id, event_type)` (`ON CONFLICT DO NOTHING` — no duplicate,
    original row untouched).
- [ ] **[Integration]** `SubscriptionSeedLoaderIT` (`@SpringBootTest` with
      `notifications.subscription-seed.enabled=true` + Testcontainers Postgres) — booting the
      context runs the loader and persists all 4 subscription rows; a second run (restart) adds
      **no** duplicates. Confirms with the flag `false` the loader does not run.
- [ ] **[Integration]** `EventSeedLoaderIT` (update) — with **both** seed loaders enabled and
      the subscription loader ordered first, booting persists **9** notifications as `PENDING`
      and skips EVT009 (`credit_cashback`, `CLIENT003` — unsubscribed); every persisted row
      carries a non-null `target_url` resolved from its subscription; a second run is idempotent
      (still 9, no duplicates, no cross-client rows).

## Smoke test & wrap-up

- [ ] Work through `validations.md` top to bottom; check off each item as observed.
- [ ] `./gradlew build` green from a clean checkout (unit tests hermetic; integration tests
      pass against Testcontainers, Docker running).
- [ ] `docker compose up notifications-db flyway -d`, then run the app with the `local`
      profile; confirm both loaders log (4 subscriptions, 9 events ingested), and
      `SELECT count(*) FROM notifications` returns **9** with **no** row for
      `client_id = 'CLIENT003' AND event_type = 'credit_cashback'`, and every notification has a
      non-null `target_url`. Restart and confirm counts stay 4 / 9 (idempotent).
- [ ] Confirm the Dependency Rule still holds: `delivery/application` imports only its own
      `domain` (the `SubscriptionPort` it now uses lives in `delivery/domain/port/out`);
      `subscription/application` imports only its own `domain`; only the
      `subscription/adapter/out` adapter references `delivery`'s `SubscriptionPort` /
      `SubscriptionLookup` (the intended cross-context seam).
- [ ] Open the PR; confirm CI is green.

# P-05 · Subscription gate — Validations

Each item is independently verifiable. Check only what you have actually observed.

## Read side — subscription resolution (the gate)

- [x] `R2dbcSubscriptionRepository` (in `subscription/adapter/out/`) implements
      `delivery.domain.port.out.SubscriptionPort` using `DatabaseClient`.
- [x] `resolve(clientId, eventType)` returns a `SubscriptionLookup(targetUrl, secret)` for an
      active matching subscription and an empty `Mono` when none matches.
- [x] Resolution is `active`-only, client-scoped, and matches an exact `event_type` **or** the
      `'*'` wildcard, preferring the exact match when both exist.
- [x] All subscription SQL is parameterized (no string concatenation).

## Write side — subscription seed persistence

- [x] `RegisterSubscriptionUseCase` (inbound port) is implemented by `RegisterSubscriptionService`
      (`@Service`), which persists via the `SubscriptionStorePort` outbound port.
- [x] `R2dbcSubscriptionRepository` also implements `SubscriptionStorePort`; `save` issues
      `INSERT ... ON CONFLICT (client_id, event_type) DO NOTHING` (idempotent; `id`/`created_at`
      from DB defaults).

## Delivery — gated ingest

- [x] `IngestEventService` injects `SubscriptionPort` and resolves the subscription **before**
      persisting.
- [x] On a match it saves a `PENDING` notification whose `targetUrl` is the resolved URL (no
      longer `null`); on no match it completes without saving and logs a skip line — `store.save`
      is not called.
- [x] The success path does not trip the skip branch (`store.save` returns a non-empty
      `Mono<Notification>`); no `.block()` anywhere.

## Subscription seed loader (inbound adapter)

- [x] `SubscriptionSeedLoader` is an `ApplicationRunner` gated by
      `@ConditionalOnProperty("notifications.subscription-seed.enabled", matchIfMissing = true)`
      and `@Order`ed to run **before** `EventSeedLoader`.
- [x] It reads `subscriptions.json`, maps each entry to a `SubscriptionRegistration`
      (`client_id → clientId`, `target_url → targetUrl`, `active` default `true`), and registers
      each via `RegisterSubscriptionUseCase` without `.block()`.
- [x] A malformed single entry is logged and skipped without aborting startup (per-entry
      `try/catch` in `SubscriptionSeedParser`, mirroring the proven P-04 event parser).

## Configuration

- [x] `subscriptions.json` exists under `src/main/resources/` (bundled on the classpath) with
      the coverage: CLIENT001 `'*'`, CLIENT002 `'*'`, CLIENT003 `credit_refund`, CLIENT003
      `debit_transfer` — leaving CLIENT003/`credit_cashback` (EVT009) uncovered.
- [x] `application.yml` declares `notifications.subscription-seed.enabled: true` and
      `notifications.subscription-seed.location: classpath:subscriptions.json`.
- [x] `src/test/resources/application.yml` sets `notifications.subscription-seed.enabled: false`.

## Tests (one checkbox per required test)

- [x] **[Unit]** `IngestEventServiceTest` passes — *subscribed*: `resolve` returns a lookup,
      `save` is called once with a `PENDING` notification carrying the resolved `targetUrl`, the
      `Mono` completes; *not subscribed*: `resolve` empty, `store.save` is never called, the
      `Mono` still completes.
- [x] **[Unit]** `SubscriptionSeedFileMapperTest` passes — `subscriptions.json` parses to 4
      `SubscriptionRegistration`s with correct field mapping (`target_url → targetUrl`, wildcard
      preserved) and `active` defaulting to `true`.
- [x] **[Integration]** `R2dbcSubscriptionRepositoryIT` passes (Testcontainers Postgres +
      `StepVerifier`) — exact match resolves `target_url`+`secret`; wildcard resolves for any
      event type; exact wins over wildcard; `active = false` → empty; different `client_id` →
      empty; unknown event type → empty; `save` idempotent on `(client_id, event_type)`.
- [x] **[Integration]** `SubscriptionSeedLoaderIT` passes — booting with
      `notifications.subscription-seed.enabled=true` persists all 4 subscription rows; a second
      run adds no duplicates; with the flag `false` the loader does not run.
- [x] **[Integration]** `EventSeedLoaderIT` (updated) passes — with both loaders enabled
      (subscription loader first), booting persists **9** notifications as `PENDING`, skips EVT009
      (`CLIENT003`/`credit_cashback`), every persisted row has a non-null `target_url`, and a
      second run stays at 9 with no duplicates and no cross-client rows.

## CI & merge criteria

- [x] `./gradlew build` succeeds from a clean checkout: unit tests are hermetic (no Spring /
      container), integration tests pass against Testcontainers Postgres (Docker running).
- [ ] Local smoke: `docker compose up notifications-db flyway -d`, run the app with the `local`
      profile → loaders log 4 subscriptions and 9 events ingested; `SELECT count(*) FROM
      notifications` returns 9 with no `CLIENT003`/`credit_cashback` row and every row has a
      non-null `target_url`; a restart keeps counts at 4 / 9 (idempotent).
- [x] Dependency Rule holds: `delivery/application` uses only its own `SubscriptionPort`;
      `subscription/application`/`domain` import no adapter/web/R2DBC types; only
      `subscription/adapter/out` references `delivery`'s `SubscriptionPort` / `SubscriptionLookup`.
- [ ] The CI workflow run on the `feature/p-05-subscription-gate` branch is **green**.
- [ ] **Merge criteria:** all boxes above checked, CI green, no code beyond the P-05 scope (no
      SSRF/URL validation, webhook, delivery/claim, endpoint, or schema change), and the PR
      reviewed and approved.

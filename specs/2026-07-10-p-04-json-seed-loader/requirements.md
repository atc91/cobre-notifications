# P-04 · JSON seed loader — Requirements

## Scope

P-04 delivers the **first working ingestion path**: platform events flow from
`notification_events.json` into the `notifications` table as `PENDING` rows, through the
`delivery` hexagon's `IngestEventUseCase` and a real R2DBC persistence adapter. It gives
`NotificationStorePort` its production adapter (for the ingest half only) and provides the
`ClockPort` production binding. The seed loader is the MVP's `IngestEventUseCase` driving
adapter — the same use case a Kafka consumer will later drive (P-14), unchanged.

This phase **does not**: check subscriptions or resolve `targetUrl` (P-05 — `targetUrl` is
persisted `null` here); call any webhook or attempt delivery (P-06+); claim due work or
record delivery attempts (P-07/P-08 — those `NotificationStorePort` methods are stubbed);
add any REST endpoint (P-09+); add or change a Flyway migration (P-02 owns the schema); or
introduce Kafka (P-14).

## Functional requirements

1. **Use-case implementation.** `IngestEventService` implements `IngestEventUseCase`. Given a
   `PlatformEvent`, it constructs a `PENDING` `Notification` (id = `event_id`, `attempts = 0`,
   `targetUrl = null`, `createdAt`/`updatedAt` = `ClockPort.now()`) and persists it via
   `NotificationStorePort.save`, returning `Mono<Void>`.
2. **Persist-first, single write.** Ingest performs exactly one DB write per event and never
   calls a webhook — the persisted row is the source of truth (inbox pattern). Delivery is a
   later phase acting on the stored row.
3. **Idempotent ingest.** Re-ingesting the same `event_id` is a harmless no-op: `save` issues
   `INSERT ... ON CONFLICT (id) DO NOTHING`, so a duplicate neither errors, duplicates, nor
   overwrites the existing row. This makes at-least-once redelivery (later Kafka) and loader
   restarts safe.
4. **R2DBC store adapter.** `R2dbcNotificationRepository implements NotificationStorePort`
   using `DatabaseClient`. `save` and `findById` are fully implemented and map faithfully
   between the `Notification` aggregate and the `notifications` table (all P-02 columns;
   `delivery_status` ↔ `DeliveryStatus`; `TIMESTAMPTZ` ↔ `Instant`). `claimDue` and
   `recordAttempt` throw `UnsupportedOperationException` (owned by P-07/P-08).
5. **Clock binding.** `SystemClockPort implements ClockPort` supplies the production current
   instant; tests substitute a fixed clock for determinism.
6. **JSON seed adapter.** `JsonSeedLoader` (an `ApplicationRunner`) reads the seed resource,
   maps each JSON entry to a `PlatformEvent` (`event_id → eventId`, `event_type → eventType`,
   `content`, `client_id → clientId`, `delivery_date → occurredAt`), and ingests each via
   `IngestEventUseCase`, composed reactively without `.block()`.
7. **Seed status is ignored.** The seed's `delivery_status` (`completed`/`failed`) and
   `delivery_date` do not set the persisted status — every seeded event is ingested as
   `PENDING`. The delivery lifecycle is driven only by the pipeline (P-06+), never by seed
   data.
8. **Configurable activation.** The loader is gated by `notifications.seed.enabled` (default
   `true`); the test profile sets it `false` so `@SpringBootTest` contexts do not auto-seed.
   The seed location is configurable via `notifications.seed.location` (default
   `classpath:notification_events.json`).
9. **Bundled resource.** `notification_events.json` ships on the classpath
   (`src/main/resources/`) so the packaged jar / Docker image is self-contained.
10. **Resilient parsing.** A malformed individual seed entry is logged and skipped; it does
    not abort startup or block ingestion of the remaining valid entries.

## Non-functional requirements

- **Reactive purity.** No `.block()` anywhere; the service composes `Mono`/`Flux`, the adapter
  returns reactive types, and the loader drives ingestion reactively. (Architecture hard rule.)
- **Dependency Rule.** `delivery/application` and `delivery/domain` import no `adapter`, web,
  R2DBC, or Kafka types; the adapter depends inward on ports only.
- **Observability seed.** The loader logs a concise startup summary (count ingested) carrying
  `event_id` context, aligning with the `event_id`-correlated logging the pipeline will build
  on (P-13).

## Out of scope

- Subscription gating and `targetUrl` resolution (P-05).
- Webhook client / SSRF guard (P-06) and any actual HTTP delivery.
- Due-work claiming (`FOR UPDATE SKIP LOCKED`), delivery attempts, retry/backoff (P-07/P-08).
- The three REST endpoints and client scoping (P-09–P-12).
- Kafka inbound adapter (P-14).
- Any new or altered database schema / Flyway migration.

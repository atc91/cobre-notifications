# P-09 / P-10 / P-11 — Self-service `notification_events` API

## Scope

This slice delivers the three mandated self-service REST endpoints over the `delivery`
aggregate, implemented in the `query` bounded context:

- `GET /notification_events` — list a client's notifications, filtered by creation-date range and
  `delivery_status`, paginated.
- `GET /notification_events/{id}` — inspect a single notification plus its delivery-attempt history.
- `POST /notification_events/{id}/replay` — re-enqueue a dead-lettered (`FAILED`) notification.

It also introduces the shared HTTP surface these endpoints need: the `GlobalExceptionHandler` and
`common` exception hierarchy (exception→HTTP mapping), the reactive `NotificationEventController`,
the response DTOs, and the **dedicated delivery-owned read/replay ports** through which `query`
reaches `delivery`'s tables without a cross-context SQL join.

Because authentication is a later phase (P-12), the calling client is identified for now by a
required **`X-Client-Id` request header** — an explicit, documented stand-in that P-12's
OAuth2 / API-key layer will replace. The service-layer client-scoping seam (OWASP A01) is built and
tested now; only the *source* of `clientId` changes in P-12.

**This phase delivers:** the read + command surface, client scoping enforced at the service layer,
exception→HTTP mapping, and the replay path that closes the loop back into the P-07/P-08 retry engine.

**This phase does NOT deliver:** real authentication / token validation / rate limiting (P-12),
observability metrics and structured logs (P-13), the Kafka inbound adapter (P-14), or any new
database migration (the required `idx_notifications_client_created` index already exists from P-02).

## Functional requirements

### Listing — `GET /notification_events`

1. The endpoint returns only notifications whose `client_id` equals the caller's `X-Client-Id`.
   A client can never see another client's notifications (data isolation).
2. Results are filterable by `created_from` and `created_to` (inclusive ISO-8601 instants) applied
   to `created_at`, and by `delivery_status` (one of `PENDING|DELIVERING|RETRYING|DELIVERED|FAILED`).
   All filters are optional and combine with AND.
3. Results are paginated with `page` (0-based, default `0`) and `size` (default `20`, capped at a
   configured maximum, e.g. `100`). The response is an envelope
   `{ content: [...], page, size, total_elements }` where `total_elements` is the total count of
   the client's notifications matching the filters.
4. Results are ordered by `created_at` descending (newest first), served by the existing
   `idx_notifications_client_created` index.
5. Each list item is a summary view: `id`, `event_type`, `delivery_status`, `attempts`,
   `created_at`, `updated_at`, `next_retry_at`.
6. An invalid filter value (`delivery_status` not in the enum, unparseable date, `created_from`
   after `created_to`, `page < 0`, `size < 1`) yields **400 Bad Request**.

### Detail — `GET /notification_events/{id}`

7. Returns the full notification (summary fields plus `content`, `target_url`, `last_error`,
   `delivered_at`) **and** its append-only delivery-attempt history — each attempt's `attempt_no`,
   `attempted_at`, `http_status`, `error`, `duration_ms` — ordered by `attempt_no` ascending.
8. Returns **404 Not Found** when the id does not exist **or** exists but is not owned by the
   caller. Ownership must not leak: a not-owned id is indistinguishable from a missing id
   (no 403-vs-404 oracle).

### Replay — `POST /notification_events/{id}/replay`

9. Re-enqueues a notification whose `delivery_status` is `FAILED`: the `delivery` aggregate
   transitions `FAILED → PENDING`, sets `next_retry_at = now`, and clears `claimed_at` / `last_error`.
   The aggregate remains the sole author of the transition (`Notification.replay`).
10. Returns **409 Conflict** when the notification exists and is owned but is not in `FAILED`
    status (e.g. `PENDING`, `DELIVERING`, `RETRYING`, `DELIVERED`).
11. Returns **404 Not Found** when the id does not exist or is not owned by the caller — checked
    before the status guard, so ownership never leaks via a 409.
12. After a successful replay the notification is due immediately, so the existing
    `DueDeliveryScheduler` / `DeliverNotificationUseCase` (P-07/P-08) picks it up and drives a fresh
    delivery cycle — no separate re-delivery code path.

### Cross-cutting

13. Every endpoint requires the `X-Client-Id` header; a missing or blank header yields
    **401 Unauthorized** (the pre-auth stand-in for P-12).
14. Client scoping is enforced in the **service/port layer** (the read and replay ports filter by
    `clientId` in SQL), not merely in the controller — so the guarantee survives even if a new
    driving adapter is added later.
15. Exception→HTTP mapping is centralized in a single `GlobalExceptionHandler`:
    `NotFoundException`→404, `ConflictException`→409, `BadRequestException`→400,
    `UnauthorizedException`→401. Services throw; the controller owns `ResponseEntity`.

## Non-functional requirements

- **Reactive contract (hard constraint):** the controller returns `Mono<ResponseEntity<T>>` /
  `Flux<T>`; services compose `Mono`/`Flux`; `.block()` is forbidden; all persistence goes through
  R2DBC. Concurrency uses `StepVerifier` in tests.
- **Security — OWASP A01 (Broken Access Control):** client scoping enforced at the service layer,
  not-owned access returns 404 (no existence leak), verified in integration tests. This is the
  phase's primary security deliverable.
- **Security — A03 (Injection):** all new SQL uses parameterized R2DBC binds only; filter values
  are never string-concatenated into SQL.
- **Hexagonal boundary:** `query` owns no tables; it reads/mutates `delivery` data exclusively
  through the new delivery-owned `NotificationReadPort` / `NotificationReplayPort`. No cross-context
  SQL join, no `query` R2DBC adapter over the notification tables. `domain`/`application` import no
  Spring/R2DBC/web types.

## Out of scope

- Authentication, token/API-key validation, and per-client rate limiting (P-12) — replaced source
  of `clientId` only.
- Metrics, structured JSON logs, tracing, and dashboards for these endpoints (P-13).
- Kafka ingestion (P-14) and the scripted end-to-end walkthrough + deliverables (P-15).
- Any new Flyway migration or schema change (the needed index already exists from P-02).
- Cursor/keyset pagination — offset `page`/`size` is sufficient for this phase.
- HMAC payload signing and other supporting OWASP measures (post-MVP / P-12+).

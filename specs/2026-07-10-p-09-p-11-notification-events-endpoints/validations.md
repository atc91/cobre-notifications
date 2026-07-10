# Validations — P-09 / P-10 / P-11: self-service `notification_events` API

Each item is independently verifiable. Group headings are context only — the `- [ ]` lines are the
checks.

## `GET /notification_events` (P-09)

- [ ] With header `X-Client-Id: CLIENT001`, the response contains only `CLIENT001` notifications; a
      second client's rows never appear.
- [ ] `created_from` / `created_to` narrow results to notifications whose `created_at` is within the
      inclusive range.
- [ ] `delivery_status=FAILED` returns only `FAILED` notifications; other statuses combine with the
      date filter via AND.
- [ ] `page` / `size` slice the results; `size` beyond the configured cap is clamped; the envelope
      returns `content`, `page`, `size`, and a correct `total_elements`.
- [ ] Results are ordered by `created_at` descending.
- [ ] Each list item exposes `id`, `event_type`, `delivery_status`, `attempts`, `created_at`,
      `updated_at`, `next_retry_at` in snake_case JSON.
- [ ] Invalid `delivery_status`, unparseable date, `created_from` after `created_to`, `page < 0`, or
      `size < 1` returns **400**.
- [ ] A request without `X-Client-Id` returns **401**.

## `GET /notification_events/{id}` (P-10)

- [ ] For an owned id, the response includes the full notification (`content`, `target_url`,
      `last_error`, `delivered_at`, status fields) **and** the delivery-attempt history ordered by
      `attempt_no`.
- [ ] An id owned by another client returns **404** (identical to a missing id — no existence leak).
- [ ] A non-existent id returns **404**.
- [ ] A request without `X-Client-Id` returns **401**.

## `POST /notification_events/{id}/replay` (P-11)

- [ ] Replaying a `FAILED` notification returns **202** (or 200) and resets the row to `PENDING` with
      `next_retry_at = now` and `claimed_at` / `last_error` cleared.
- [ ] Replaying a non-`FAILED` notification (`PENDING`/`DELIVERING`/`RETRYING`/`DELIVERED`) returns
      **409** and leaves the row unchanged.
- [ ] Replaying an id owned by another client, or a missing id, returns **404** (checked before the
      status guard, so no 409 existence leak).
- [ ] A request without `X-Client-Id` returns **401**.

## Cross-cutting

- [ ] `query` contains no R2DBC adapter over the notification tables and no cross-context SQL join;
      all access goes through `NotificationReadPort` / `NotificationReplayPort` (delivery-owned).
- [ ] Client scoping is enforced in the port/service layer (SQL filters by `clientId`), not only in
      the controller.
- [ ] `GlobalExceptionHandler` maps `NotFoundException`→404, `ConflictException`→409,
      `BadRequestException`→400, `UnauthorizedException`→401; controllers return `ResponseEntity`,
      services throw.
- [ ] No new Flyway migration was added; the list query uses the existing
      `idx_notifications_client_created` index.
- [ ] No `.block()` anywhere in the new code; controller returns `Mono<ResponseEntity<T>>` / `Flux<T>`.

## Required tests (per layer)

- [ ] **Unit** — `PageRequest` bounds: defaults, `size` cap, and rejection of `page < 0` / `size < 1`.
- [ ] **Unit** — `NotificationFilter`: `created_from` after `created_to` rejected; all-null allowed.
- [ ] **Integration** — list is client-scoped (client A never sees client B).
- [ ] **Integration** — list filtering (`created_from`/`created_to`, `delivery_status`) + pagination
      (`page`/`size`, `total_elements`, `created_at DESC` ordering).
- [ ] **Integration** — list returns 400 on invalid filter/paging and 401 on missing `X-Client-Id`.
- [ ] **Integration** — detail returns notification + attempt history for the owner; 404 for
      not-owned and for absent.
- [ ] **Integration** — replay: FAILED→202 with correct row reset; non-FAILED→409; not-owned/absent→404;
      missing header→401.
- [ ] **E2E / walkthrough** — replay a `FAILED` notification, then `deliverDue()` against a 200
      `MockWebServer` drives it to `DELIVERED` with a fresh `delivery_attempts` row.

## CI & merge

- [ ] `./gradlew test` passes locally (all new unit, integration, and E2E tests green).
- [ ] CI is green on `feature/p-09-p-11-notification-events-endpoints`; **merge criteria:** all
      checkboxes above ticked, all three endpoints return the specified status codes, and client
      scoping (OWASP A01) is verified by an integration test.

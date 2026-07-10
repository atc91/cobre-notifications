# Plan — P-09 / P-10 / P-11: self-service `notification_events` API

Endpoints live in the `query` hexagon (`query.domain` / `query.application` /
`query.adapter.in`). `query` owns no data: it reaches `delivery`'s `notifications` /
`delivery_attempts` tables only through two **new delivery-owned outbound ports**
(`NotificationReadPort`, `NotificationReplayPort`) implemented by the delivery R2DBC adapter.
See `requirements.md` for the contract and `validations.md` for the checklist.

## Backend — `delivery` context (data-owning side)

- [ ] Add `NotificationFilter` value object (`delivery.domain.model` or `.port.out`): `createdFrom`,
      `createdTo`, `status` (all nullable). Validates `createdFrom <= createdTo`.
- [ ] Add `NotificationPage` holder (content `List<Notification>` + `long totalElements`) returned by
      the read port, or return `content` + `count` as two composable reactive calls.
- [ ] Add `NotificationReadPort` (`delivery.domain.port.out`): client-scoped `findPage(clientId,
      NotificationFilter, page, size)` returning content + `total_elements`; client-scoped
      `findByIdForClient(id, clientId)` → `Mono<Notification>` (empty when absent or not owned);
      `findAttempts(notificationId)` → `Flux<DeliveryAttempt>` for the detail view.
- [ ] Add `NotificationReplayPort` (`delivery.domain.port.out`): `replay(id, clientId, now)` that,
      inside one transaction, loads the row by id+client, throws `NotFoundException` when absent/not
      owned and `ConflictException` when not `FAILED`, otherwise calls `Notification.replay(now)` and
      persists the new state (status `PENDING`, `next_retry_at = now`, `claimed_at`/`last_error`
      cleared). Aggregate stays the sole author of the transition.

## Persistence — `delivery` R2DBC adapter

- [ ] Extend `R2dbcNotificationRepository` (or add a sibling read adapter) to implement
      `NotificationReadPort`: parameterized `SELECT ... WHERE client_id = :c` plus optional
      `created_at >= :from`, `created_at <= :to`, `delivery_status = :status`, ordered
      `created_at DESC`, `LIMIT :size OFFSET :page*size`; a matching `SELECT count(*)` for
      `total_elements`; a `findByIdForClient` selecting on `id` AND `client_id`; and
      `SELECT * FROM delivery_attempts WHERE notification_id = :id ORDER BY attempt_no`.
- [ ] Implement `NotificationReplayPort` in the R2DBC adapter: transactional
      select-guard-`Notification.replay()`-update, reusing the existing `UPDATE_STATE` statement
      (no `delivery_attempts` row is written on replay).
- [ ] Confirm no new migration is needed — reuse `idx_notifications_client_created` (P-02) for the
      list query; add a code comment referencing it.

## Backend — `query` context (read + command surface)

- [ ] `query.domain.port.in.QueryNotificationsUseCase`: `list(clientId, NotificationFilter,
      PageRequest)` → `Mono<NotificationPage>`; `get(clientId, id)` → `Mono<NotificationDetail>`
      (or a domain view that the adapter maps).
- [ ] `query.domain.port.in.ReplayNotificationUseCase`: `replay(clientId, id)` → `Mono<Void>`.
- [ ] `query.domain.model.PageRequest` value object: `page`/`size` with defaults and max-size cap;
      rejects `page < 0` / `size < 1` with `BadRequestException`.
- [ ] `query.application.QueryNotificationsService` implements `QueryNotificationsUseCase`, depends
      on `NotificationReadPort`; enforces client scoping by always passing `clientId`; maps a missing
      `get` result to `NotFoundException`.
- [ ] `query.application.ReplayNotificationService` implements `ReplayNotificationUseCase`, depends
      on `NotificationReplayPort`; delegates the guard + transition to the port.

## API — controller, DTOs, error mapping (`query.adapter.in` + `common`)

- [ ] `common` exception hierarchy: `NotFoundException`, `ConflictException`, `BadRequestException`,
      `UnauthorizedException` (unchecked, message-carrying).
- [ ] `common.GlobalExceptionHandler` (`@RestControllerAdvice`): maps the four exceptions to
      404 / 409 / 400 / 401 with a small JSON error body `{ error, message }`. Single source of
      exception→HTTP mapping for the whole service.
- [ ] `NotificationEventController` (`query.adapter.in`, `@RestController`, path
      `/notification_events`): reads the required `X-Client-Id` header (missing/blank →
      `UnauthorizedException`); reactive signatures returning `Mono<ResponseEntity<T>>`.
  - [ ] `GET /notification_events` → parse `created_from`/`created_to`/`delivery_status`/`page`/`size`,
        build `NotificationFilter` + `PageRequest`, return `200` with the page envelope.
  - [ ] `GET /notification_events/{id}` → `200` with detail + attempts, or `404`.
  - [ ] `POST /notification_events/{id}/replay` → `202 Accepted` (or `200`) on success, `409`/`404`
        via thrown exceptions.
- [ ] Response DTOs (records) in `query.adapter.in`: `NotificationSummary`, `NotificationDetail`,
      `DeliveryAttemptView`, `PageResponse<T>` (`content`, `page`, `size`, `total_elements`).
      Serialize JSON as **snake_case** (global naming strategy or per-field `@JsonProperty`),
      consistent with the existing webhook payload and the `created_from`/`delivery_status`
      query-param contract.
- [ ] Expose the three endpoints/actuator as needed; no security config yet (P-12).

## Tests

- [ ] **Unit** — `PageRequest` bounds: defaults applied, `size` capped at max, `page < 0` and
      `size < 1` rejected with `BadRequestException`. (`query.domain.model`)
- [ ] **Unit** — `NotificationFilter`: `created_from` after `created_to` rejected; all-null filter
      is permitted. (`delivery.domain`)
- [ ] **Integration** — `GET /notification_events` client scoping: client A sees only A's rows,
      never B's, via `WebTestClient` + Testcontainers Postgres.
- [ ] **Integration** — `GET /notification_events` filtering + pagination: `created_from`/`created_to`
      and `delivery_status` filters narrow results; `page`/`size` slice correctly; `total_elements`
      is the full filtered count; ordering is `created_at DESC`.
- [ ] **Integration** — `GET /notification_events` returns `400` on invalid `delivery_status` /
      unparseable date / bad paging, and `401` when `X-Client-Id` is absent.
- [ ] **Integration** — `GET /notification_events/{id}` returns notification **+** delivery-attempt
      history for the owner; `404` when the id belongs to another client; `404` when absent.
- [ ] **Integration** — `POST /{id}/replay`: `FAILED` → `202`, row reset to `PENDING` with
      `next_retry_at = now` and cleared `claimed_at`/`last_error`; non-`FAILED` → `409`; not-owned /
      absent → `404`; missing header → `401`.
- [ ] **E2E / walkthrough** — replay closes the loop: seed a `FAILED` notification, `POST /replay`,
      then run `DeliverNotificationUseCase.deliverDue()` against a `MockWebServer` returning `200` and
      assert the row reaches `DELIVERED` with a new `delivery_attempts` row — proving replay
      re-enters the P-07/P-08 retry engine.

## Smoke test & wrap-up

- [ ] Work through every checkbox in `validations.md`.
- [ ] `docker compose up`, hit all three endpoints with `curl` (with and without `X-Client-Id`),
      confirm 200 / 404 / 409 / 401 as specified.
- [ ] `./gradlew test` green (new unit + integration + E2E tests); CI passes on the branch.

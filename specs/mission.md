# Mission

Cobre Notifications exists to deliver every platform-generated event to the right client's webhook — reliably, exactly to those who are subscribed, with a full, queryable record of what happened.

## The problem

Cobre's platform is transactional, cloud-native, and event-driven: accounts, payments, and transactions continuously generate events (a balance update, a payment received, a transfer sent). Clients want to react to those events in their own systems in near real-time.

Doing this well is harder than "POST to a URL":

- **Relevance** — a client must only ever receive events that belong to *them*. Leaking another client's event is a data-isolation incident.
- **Reliability** — client endpoints fail, time out, and rate-limit. A single failed HTTP call must not mean a lost notification.
- **Accountability** — when a client complains "I never got my notification," the monitoring team needs an authoritative answer in seconds, not a log grep across services.
- **Self-service** — clients want to inspect and re-drive their own notifications without opening a support ticket.

## The solution

A dedicated notifications service with two faces:

- **Delivery pipeline** — consume platform events, confirm the client is **subscribed** to that event, deliver it over **HTTPS** to the client's webhook, **retry** failures with an efficient backoff strategy, and **persist the final outcome** of every attempt. The pipeline is **observable in near real-time** so the internal monitoring team can spot deviations and answer client complaints promptly.
- **Self-service API** — a REST API where a client can **list** their notification events (filtered by creation date and delivery status), **inspect** a single event, and **replay** a notification whose delivery has definitively failed.

## Who it's for

| Actor | Primary need |
|---|---|
| **Client system** | Receive relevant events at its webhook, reliably and in order of best effort |
| **Client developer** | Self-service visibility and control over their own notifications via REST |
| **Internal monitoring team** | Near real-time observability to detect deviations and resolve complaints |
| **Cobre platform services** | A single, decoupled place to hand off events for delivery |

## Scope

**In scope (case delivery):**

- Subscription-gated delivery of events to client webhooks over HTTPS.
- Durable retry with exponential backoff and a dead-letter terminal state.
- Persistence of every notification and every delivery attempt.
- Self-service REST API: `GET /notification_events`, `GET /notification_events/{id}`, `POST /notification_events/{id}/replay`.
- Near real-time observability (metrics, structured logs, dashboards, alerts).
- A security posture addressing at least three OWASP Top 10 risks.

**Out of scope (documented as future direction):**

- Producing the platform events themselves — we consume them.
- Client-facing UI — the self-service surface is an API.
- Delivery channels other than HTTPS webhooks (email, SMS, push).

## North star

**Every subscribed event lands, or is provably accounted for.** No silent loss, no cross-client leakage, and a monitoring team that always knows the state of delivery before the client does.

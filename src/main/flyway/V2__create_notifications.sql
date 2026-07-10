-- delivery context: the Notification aggregate root.
-- id IS the platform event_id (the idempotency key), so it is VARCHAR, not a generated UUID.
CREATE TABLE notifications
(
    id              VARCHAR(100) PRIMARY KEY,
    client_id       VARCHAR(100) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    content         TEXT         NOT NULL,
    target_url      VARCHAR(2048),          -- resolved from the subscription at ingest
    delivery_status VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts        INT          NOT NULL DEFAULT 0,
    next_retry_at   TIMESTAMPTZ,            -- drives the due-work poll
    claimed_at      TIMESTAMPTZ,            -- delivery lease; lets the reaper reclaim stuck rows
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    delivered_at    TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- VARCHAR + CHECK (not a PG enum): new statuses are a plain ALTER, no R2DBC codec wiring.
    CONSTRAINT notifications_delivery_status_check
        CHECK (delivery_status IN ('PENDING', 'DELIVERING', 'RETRYING', 'DELIVERED', 'FAILED'))
);

-- Due-work poll: WHERE status IN (...) AND next_retry_at <= now (P-07 scheduler).
CREATE INDEX idx_notifications_due_work ON notifications (delivery_status, next_retry_at);
-- Client-scoped list endpoint, filtered/ordered by creation date (P-09).
CREATE INDEX idx_notifications_client_created ON notifications (client_id, created_at);

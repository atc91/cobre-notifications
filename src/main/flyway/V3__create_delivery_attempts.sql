-- delivery context: append-only audit of every delivery attempt for a notification.
CREATE TABLE delivery_attempts
(
    id              UUID PRIMARY KEY      DEFAULT uuidv7(),
    notification_id VARCHAR(100) NOT NULL REFERENCES notifications (id),
    attempt_no      INT          NOT NULL,
    attempted_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    http_status     INT,
    error           TEXT,
    duration_ms     BIGINT
);

CREATE INDEX idx_delivery_attempts_notification ON delivery_attempts (notification_id);

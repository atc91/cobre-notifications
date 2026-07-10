-- subscription context: which client is subscribed to which event, and where to deliver.
CREATE TABLE subscriptions
(
    id         UUID PRIMARY KEY       DEFAULT uuidv7(),
    client_id  VARCHAR(100)  NOT NULL,
    event_type VARCHAR(100)  NOT NULL, -- a concrete event type, or '*' for all
    target_url VARCHAR(2048) NOT NULL,
    secret     VARCHAR(255),           -- per-subscription HMAC secret (used in a later phase)
    active     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT subscriptions_client_event_unique UNIQUE (client_id, event_type)
);

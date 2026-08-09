CREATE TABLE webhook_event (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payload        TEXT NOT NULL,
    status         VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'PROCESSED', 'DROPPED', 'FAILED')),
    attempt_count  INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    error          TEXT,
    received_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at   TIMESTAMPTZ
);

-- The inbound counterpart to the outbox (ADR-012): a durable landing zone
-- so "verify signature, persist, return 200" never loses an accepted
-- delivery, with a scheduled poller (whatsapp-domain.md §4) doing the
-- actual domain processing afterward. Not itself a dedupe boundary —
-- Message's (tenant_id, wa_message_id) constraint is.
CREATE INDEX idx_webhook_event_status ON webhook_event (status);

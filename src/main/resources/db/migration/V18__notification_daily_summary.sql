-- A daily spend summary per API key.
--
-- Trustpady were repeatedly surprised by what their notifications had cost,
-- because nobody told them until the total had built up. Each UTC day that
-- a key sent anything now produces one mail the next day: how many went
-- out, how many were delivered, and what the delivered ones cost.
--
-- Per key, not per tenant. Trustpady's key lives on the operator's own
-- tenant (ADR-018), so a tenant-wide figure would show one integrator
-- another's traffic and bill them for it.
--
-- One row per (key, day) is what stops the summary going out twice, for the
-- same reason V16's ledger is in the database: the service cold starts
-- several times a day and an in-memory flag would not survive it. The row
-- also keeps the figures exactly as they were mailed, so a disputed bill can
-- be answered with what the integrator was told rather than a recount taken
-- after late delivery receipts have moved the numbers.
CREATE TABLE notification_daily_summary (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenant (id),
    api_key_id      UUID NOT NULL REFERENCES api_key (id),
    -- The UTC day summarized, not when the mail went out.
    period_start    DATE NOT NULL,
    sends           INT NOT NULL,
    delivered       INT NOT NULL,
    failed          INT NOT NULL,
    -- The rate in force when the summary was taken. Meta's cost per
    -- delivered message is an average that moves, so the configured value
    -- will change and old summaries must keep the one they were priced at.
    price_per_delivered NUMERIC(12, 4) NOT NULL,
    cost            NUMERIC(14, 4) NOT NULL,
    currency        VARCHAR(3) NOT NULL,
    summarized_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_daily_summary UNIQUE (api_key_id, period_start)
);

-- The per-key, per-day aggregate behind the summary. V12's index leads with
-- tenant_id, which on a tenant carrying several keys still reads every
-- other key's rows for the range.
CREATE INDEX idx_notification_log_key_created ON notification_log (api_key_id, created_at);

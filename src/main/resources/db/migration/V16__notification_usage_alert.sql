-- Daily send-volume alerting.
--
-- The 2026-09-11 spike (657 sends against a 200-300 baseline) ran for two
-- days and was found from a bill. Nothing watched the number, so nothing
-- said anything.
--
-- One row per (tenant, day, threshold) is what stops the same alert being
-- sent twice, and it lives in the database rather than in a
-- ConcurrentHashMap. DeliveryBlockedEmailListener keeps its cooldown in
-- memory and is right to: it guards a burst measured in minutes, so losing
-- it to a restart costs at most a duplicate alert during one outage. This
-- guard spans a whole day on a service that spins down whenever it is idle
-- and cold starts several times a day -- an in-memory version would re-alert
-- on every wake, and an alert that arrives six times a day is one nobody
-- reads.
CREATE TABLE notification_usage_alert (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenant (id),
    -- The UTC day counted, not the instant the mail went out; alerted_at
    -- records that separately. Keeping them apart is what lets a late alert
    -- still be recognised as belonging to the day it describes.
    period_start   DATE NOT NULL,
    threshold      INT NOT NULL,
    sends_at_alert INT NOT NULL,
    alerted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_usage_alert UNIQUE (tenant_id, period_start, threshold)
);

CREATE INDEX idx_notification_usage_alert_tenant_period
    ON notification_usage_alert (tenant_id, period_start DESC);

-- Where to reach the integrator behind a key.
--
-- Until now the only addresses on file were the tenant's own Owners and
-- Admins, so a partner whose client was misbehaving could only be told by
-- someone forwarding an email by hand. Optional, because a key issued for
-- a tenant's own backend has no third party to notify.
--
-- 320 characters is the maximum length of an email address (64 local + @ +
-- 255 domain), so the column cannot be the thing that truncates one.
ALTER TABLE api_key
    ADD COLUMN contact_email VARCHAR(320);

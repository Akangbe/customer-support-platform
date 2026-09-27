-- The daily volume alert counts per API key, not per tenant.
--
-- The alert goes to the key's contact, and Trustpady's key lives on the
-- operator's own tenant (ADR-018). Counted tenant-wide, the mail told one
-- integrator "you have sent 300" about traffic that included every other
-- key on the tenant -- a wrong number, and another client's volume
-- disclosed. V18's daily summary was per key from the start; this brings
-- the volume alert in line.
--
-- Rows written before this migration were counted tenant-wide and have no
-- key. They are kept, and the monitor still treats one as "already
-- alerted" for its day and threshold, so the day this ships does not
-- announce its thresholds a second time. From the next day on every row
-- carries a key and those legacy rows are simply history.
ALTER TABLE notification_usage_alert
    ADD COLUMN api_key_id UUID REFERENCES api_key (id);

ALTER TABLE notification_usage_alert DROP CONSTRAINT uq_notification_usage_alert;

ALTER TABLE notification_usage_alert
    ADD CONSTRAINT uq_notification_usage_alert_key UNIQUE (api_key_id, period_start, threshold);

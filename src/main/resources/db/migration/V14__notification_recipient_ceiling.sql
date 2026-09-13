-- Supports the per-recipient velocity ceiling: "how many times have we
-- already messaged this person with this template in the last N minutes".
--
-- The ceiling exists because nothing in the system bounded how often one
-- customer could be messaged. Between 2026-09-01 and 2026-09-12 one
-- recipient received 38 sends of the same template in 18 minutes, and the
-- per-key rate limit never fired -- at 60 requests/minute it is nowhere
-- near, and it is content-blind besides: it cannot tell one notification
-- from the same notification sent forty times.
--
-- Keyed on template as well as recipient so that a future transactional
-- template (a password reset, say) is not throttled by an unrelated
-- template's burst. The abuse observed was concentrated in a single
-- template, so this still catches it.
--
-- created_at DESC because every read is "the most recent window", never a
-- historical scan.
CREATE INDEX idx_notification_log_recipient_window
    ON notification_log (tenant_id, recipient, template_name, created_at DESC);

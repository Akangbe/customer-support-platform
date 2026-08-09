-- Retry bookkeeping for the outbound sender poller (whatsapp-domain.md §6)
-- and template-send fields (§8) — deliberately absent from V5, since
-- nothing consumed PENDING messages until now.
ALTER TABLE message ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE message ADD COLUMN next_attempt_at TIMESTAMPTZ;
ALTER TABLE message ADD COLUMN failure_reason TEXT;
ALTER TABLE message ADD COLUMN template_name VARCHAR(255);
ALTER TABLE message ADD COLUMN template_language_code VARCHAR(20);
ALTER TABLE message ADD COLUMN template_params TEXT;

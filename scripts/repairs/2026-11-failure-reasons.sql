-- One-off repair of notification_log, using the raw webhooks we already saved.
--
-- 1. Failed messages stored with the old constant reason get Meta's real error
--    back, in the format WebhookEventHandler writes today.
-- 2. Messages stuck on SENT, although a saved webhook says delivered or read,
--    move to that status.
--
-- Previews by default: it prints what would change, then rolls everything back.
--
--   Preview: psql -f 2026-11-failure-reasons.sql
--   Apply:   psql -v apply=true -f 2026-11-failure-reasons.sql
--
-- Applying keeps the old values in notification_log_repair_202611, so the
-- change can be undone. Running it a second time changes nothing.

\set ON_ERROR_STOP on
\if :{?apply}
\else
  \set apply false
\endif

BEGIN;

CREATE TEMP TABLE webhook_status ON COMMIT DROP AS
SELECT s ->> 'id'     AS meta_message_id,
       s ->> 'status' AS status,
       s -> 'errors' -> 0 AS error,
       w.received_at
  FROM webhook_event w
 CROSS JOIN LATERAL jsonb_array_elements(w.payload::jsonb -> 'entry') e
 CROSS JOIN LATERAL jsonb_array_elements(e -> 'changes') c
 CROSS JOIN LATERAL jsonb_array_elements(COALESCE(c -> 'value' -> 'statuses', '[]'::jsonb)) s
 WHERE w.payload LIKE '%"statuses"%';

CREATE INDEX ON webhook_status (meta_message_id);

CREATE TEMP TABLE reason_fix ON COMMIT DROP AS
SELECT DISTINCT ON (n.id)
       n.id,
       ws.error ->> 'code' AS code,
       'Meta error ' || (ws.error ->> 'code')
         || COALESCE(': ' || NULLIF(ws.error ->> 'title', ''), '')
         || COALESCE(' - ' || NULLIF(ws.error -> 'error_data' ->> 'details', ''), '') AS new_reason
  FROM notification_log n
  JOIN webhook_status ws ON ws.meta_message_id = n.meta_message_id
 WHERE n.status = 'FAILED'
   AND n.failure_reason = 'WhatsApp reported delivery failure'
   AND ws.status = 'failed'
   AND COALESCE(ws.error ->> 'code', '') <> ''
 ORDER BY n.id, ws.received_at DESC;

-- A day old at least, so a webhook still waiting in the queue is left to finish normally.
CREATE TEMP TABLE status_fix ON COMMIT DROP AS
SELECT n.id,
       CASE WHEN bool_or(ws.status = 'read') THEN 'READ' ELSE 'DELIVERED' END AS new_status
  FROM notification_log n
  JOIN webhook_status ws ON ws.meta_message_id = n.meta_message_id
 WHERE n.status = 'SENT'
   AND n.created_at < now() - interval '1 day'
   AND ws.status IN ('delivered', 'read')
 GROUP BY n.id;

\echo
\echo === Failure reasons that would be restored, by Meta error code
SELECT code, count(*) AS messages FROM reason_fix GROUP BY code ORDER BY count(*) DESC;

\echo === One example of each new reason
SELECT DISTINCT ON (code) code, new_reason FROM reason_fix ORDER BY code;

\echo === Statuses that would change
SELECT new_status, count(*) AS messages FROM status_fix GROUP BY new_status;

CREATE TABLE IF NOT EXISTS notification_log_repair_202611 (
    notification_id    UUID PRIMARY KEY,
    old_status         VARCHAR(20) NOT NULL,
    old_failure_reason TEXT,
    new_status         VARCHAR(20) NOT NULL,
    new_failure_reason TEXT,
    repaired_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO notification_log_repair_202611 (notification_id, old_status, old_failure_reason, new_status, new_failure_reason)
SELECT n.id, n.status, n.failure_reason,
       COALESCE(s.new_status, n.status),
       COALESCE(r.new_reason, n.failure_reason)
  FROM notification_log n
  LEFT JOIN reason_fix r ON r.id = n.id
  LEFT JOIN status_fix s ON s.id = n.id
 WHERE r.id IS NOT NULL OR s.id IS NOT NULL
ON CONFLICT (notification_id) DO NOTHING;

UPDATE notification_log n SET failure_reason = r.new_reason FROM reason_fix r WHERE r.id = n.id;
UPDATE notification_log n SET status = s.new_status FROM status_fix s WHERE s.id = n.id;

\echo === Failed messages still without a real reason afterwards
SELECT count(*) AS messages FROM notification_log
 WHERE status = 'FAILED' AND failure_reason = 'WhatsApp reported delivery failure';

\if :apply
  COMMIT;
  \echo === APPLIED. Old values are in notification_log_repair_202611.
\else
  ROLLBACK;
  \echo === PREVIEW ONLY. Nothing was changed.
\endif

-- Outbound idempotency for the notification send API.
--
-- Rule 7 and ADR-012 made *ingestion* idempotent: a webhook Meta replays
-- cannot become a second domain fact. The outbound direction never got the
-- same treatment, and it is the direction that costs money -- every POST
-- to /api/v1/notifications/send reached the Graph API unconditionally, so
-- two identical calls sent (and Meta billed) two real messages.
--
-- The key is only ever SUPPLIED, never DERIVED. That distinction is the
-- whole design and it is not a matter of taste. The one template in use
-- carries no parameters, so two requests for genuinely different events
-- are byte-identical; a key derived from the body could not tell a retry
-- from a second real notification, and would silently discard the latter
-- while reporting success. Suppressing a message the customer needed is a
-- worse failure than sending one twice. Only the caller knows whether two
-- calls mean one business event, so only the caller may say so.
ALTER TABLE notification_log
    ADD COLUMN idempotency_key VARCHAR(200);

-- PENDING is new. The send path now claims its row BEFORE calling Meta,
-- which is what gives a concurrent retry something to collide with -- the
-- old code inserted only after Meta had already been called and the
-- message already sent, far too late to prevent anything. A row left at
-- PENDING means the process died between claiming it and hearing back,
-- and that ambiguity is deliberately visible: it is cheaper to investigate
-- a PENDING row than to bill a customer twice.
ALTER TABLE notification_log DROP CONSTRAINT notification_log_status_check;
ALTER TABLE notification_log ADD CONSTRAINT notification_log_status_check
    CHECK (status IN ('PENDING', 'SENT', 'DELIVERED', 'READ', 'FAILED'));

-- Partial, because rows written before this migration -- and every send
-- from a caller that does not supply the header -- have no key, and must
-- continue to coexist without constraining each other.
--
-- Tenant-scoped for the same reason as every other index here (Rule 3):
-- one tenant's chosen key must never collide with another's.
CREATE UNIQUE INDEX uq_notification_log_tenant_idempotency_key
    ON notification_log (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

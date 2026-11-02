-- A client account is one business that sends through this platform.
--
-- tenant_id cannot be that boundary on its own: Trustpady sends through the
-- operator's WhatsApp number, so its key sits on the operator's tenant, and
-- any other business on that number would share the tenant too. Anything a
-- client can read is therefore scoped to its account as well as its tenant.
--
-- The account is separate from the API key so that rotating a key does not
-- orphan a client's history, usage or idempotency keys.
CREATE TABLE client_account (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenant (id),
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The target of the composite foreign keys below, which make it
    -- impossible to attach a row to an account in a different tenant.
    CONSTRAINT uq_client_account_id_tenant UNIQUE (id, tenant_id)
);

CREATE INDEX idx_client_account_tenant ON client_account (tenant_id);

-- One account per existing key. Reusing the key's id as the account's id is
-- what lets the next statements map one to the other without a lookup table.
-- Keys that belong to the same business can be merged later by repointing them.
INSERT INTO client_account (id, tenant_id, name, created_at)
SELECT id, tenant_id, name, created_at FROM api_key;

ALTER TABLE api_key ADD COLUMN client_account_id UUID;
UPDATE api_key SET client_account_id = id;
ALTER TABLE api_key ALTER COLUMN client_account_id SET NOT NULL;
ALTER TABLE api_key ADD CONSTRAINT fk_api_key_client_account
    FOREIGN KEY (client_account_id, tenant_id) REFERENCES client_account (id, tenant_id);
CREATE INDEX idx_api_key_client_account ON api_key (client_account_id);

-- Stamped on the message when it is written, rather than joined through the
-- key, so that moving a key to another account can never move its history.
-- Nullable only because api_key_id is: a row with no key has no client, and
-- no client-scoped query can return it.
ALTER TABLE notification_log ADD COLUMN client_account_id UUID;
UPDATE notification_log n
   SET client_account_id = k.client_account_id
  FROM api_key k
 WHERE k.id = n.api_key_id;
ALTER TABLE notification_log ADD CONSTRAINT fk_notification_log_client_account
    FOREIGN KEY (client_account_id, tenant_id) REFERENCES client_account (id, tenant_id);
CREATE INDEX idx_notification_log_client_created
    ON notification_log (client_account_id, created_at DESC);

-- Scoped per tenant (V15), one client reusing another's Idempotency-Key was
-- answered from the other's record and its own message was never sent.
DROP INDEX uq_notification_log_tenant_idempotency_key;
CREATE UNIQUE INDEX uq_notification_log_client_idempotency_key
    ON notification_log (client_account_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

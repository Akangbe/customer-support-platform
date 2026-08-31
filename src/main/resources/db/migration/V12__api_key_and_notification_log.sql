-- Machine-to-machine credentials for the notification-send API. A tenant
-- app (e.g. Trustpady) authenticates with `rd_live_{key_id}.{secret}`:
-- key_id is the plaintext lookup handle, secret_hash is the only thing we
-- ever store of the secret half (NFR-SEC, same posture as app_user.password_hash).
CREATE TABLE api_key (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenant (id),
    key_id        VARCHAR(64) NOT NULL UNIQUE,
    secret_hash   VARCHAR(255) NOT NULL,
    name          VARCHAR(200) NOT NULL,
    rate_limit    INT NOT NULL DEFAULT 60,
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    last_used_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at    TIMESTAMPTZ
);

-- key_id is deliberately NOT tenant-scoped: it is the join key the caller
-- presents before any tenant is known, so it resolves the tenant itself
-- (the same reasoning as whatsapp_connection.phone_number_id in V6).
-- The UNIQUE constraint above is that lookup index.
CREATE INDEX idx_api_key_tenant ON api_key (tenant_id);

-- Every send attempt through /api/v1/notifications/send, sent or failed.
-- Separate from `message`: these are fire-and-forget template notifications
-- from a tenant's own backend, not agent replies inside a conversation, and
-- they have no conversation_id or customer to hang off (Rule 1).
CREATE TABLE notification_log (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES tenant (id),
    api_key_id       UUID REFERENCES api_key (id),
    recipient        VARCHAR(20) NOT NULL,
    template_name    VARCHAR(255) NOT NULL,
    language_code    VARCHAR(20) NOT NULL,
    status           VARCHAR(20) NOT NULL CHECK (status IN ('SENT', 'DELIVERED', 'READ', 'FAILED')),
    meta_message_id  VARCHAR(255),
    failure_reason   TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_log_tenant_created ON notification_log (tenant_id, created_at DESC);

-- The lookup behind both the tenant-facing status endpoint and the delivery
-- webhook: Meta's own id is how a caller (and Meta) refers to a send.
-- Tenant-scoped, unlike api_key.key_id, because the tenant is already known
-- from the API key or the resolved phone_number_id by the time we get here.
CREATE UNIQUE INDEX uq_notification_log_tenant_meta_message_id
    ON notification_log (tenant_id, meta_message_id) WHERE meta_message_id IS NOT NULL;

-- Issuing or revoking a machine credential is a configuration change in the
-- FR-AUD-003 sense, exactly as connecting WhatsApp is. V9 pinned the action
-- set with a CHECK, so extending the enum means replacing that constraint.
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'USER_INVITED', 'USER_ROLE_CHANGED', 'USER_DISABLED', 'USER_ENABLED',
    'CONVERSATION_ASSIGNED', 'CONVERSATION_UNASSIGNED', 'WHATSAPP_CONNECTED',
    'API_KEY_CREATED', 'API_KEY_DEACTIVATED', 'API_KEY_REACTIVATED'
));

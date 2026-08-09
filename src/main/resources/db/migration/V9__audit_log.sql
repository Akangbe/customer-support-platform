CREATE TABLE audit_log (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenant (id),
    actor_user_id  UUID NOT NULL REFERENCES app_user (id),
    action         VARCHAR(40) NOT NULL CHECK (action IN (
                       'USER_INVITED', 'USER_ROLE_CHANGED', 'USER_DISABLED', 'USER_ENABLED',
                       'CONVERSATION_ASSIGNED', 'CONVERSATION_UNASSIGNED', 'WHATSAPP_CONNECTED'
                   )),
    target_type    VARCHAR(40) NOT NULL,
    target_id      UUID NOT NULL,
    detail         TEXT NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_tenant_occurred ON audit_log (tenant_id, occurred_at DESC);

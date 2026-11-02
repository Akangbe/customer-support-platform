-- One row per reset link. Only the SHA-256 of the token is kept, so a copy of
-- this table cannot be used to take over an account. Rows stay after use because
-- the hourly limit on requests counts them; a scheduled job deletes old ones.
CREATE TABLE password_reset_token (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_password_reset_token_user_created ON password_reset_token (user_id, created_at DESC);

ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'USER_INVITED', 'USER_ROLE_CHANGED', 'USER_DISABLED', 'USER_ENABLED',
    'CONVERSATION_ASSIGNED', 'CONVERSATION_UNASSIGNED', 'WHATSAPP_CONNECTED',
    'API_KEY_CREATED', 'API_KEY_UPDATED', 'API_KEY_DEACTIVATED', 'API_KEY_REACTIVATED',
    'CLIENT_RECIPIENT_VIEWED',
    'PASSWORD_RESET_REQUESTED', 'PASSWORD_RESET_COMPLETED'
));

CREATE TABLE message (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES tenant (id),
    conversation_id   UUID NOT NULL REFERENCES conversation (id),
    direction         VARCHAR(20) NOT NULL CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    status            VARCHAR(20) CHECK (status IN ('PENDING', 'SENT', 'DELIVERED', 'READ', 'FAILED')),
    body              TEXT NOT NULL,
    sender_user_id    UUID REFERENCES app_user (id),
    wa_message_id     VARCHAR(255),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_message_tenant_id ON message (tenant_id);
CREATE INDEX idx_message_conversation_id ON message (conversation_id);

-- ADR-012: dedupe key for inbound idempotency. Dormant until Phase 6
-- populates wa_message_id, but must exist on the table from day one.
CREATE UNIQUE INDEX uq_message_tenant_wa_message_id ON message (tenant_id, wa_message_id) WHERE wa_message_id IS NOT NULL;

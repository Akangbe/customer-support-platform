CREATE TABLE conversation (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES tenant (id),
    customer_id        UUID NOT NULL REFERENCES customer (id),
    status             VARCHAR(20) NOT NULL CHECK (status IN ('OPEN', 'ASSIGNED', 'CLOSED')),
    priority           VARCHAR(20) NOT NULL CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    assigned_agent_id  UUID REFERENCES app_user (id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_inbound_at    TIMESTAMPTZ,
    last_outbound_at   TIMESTAMPTZ,
    closed_at          TIMESTAMPTZ
);

CREATE INDEX idx_conversation_tenant_id ON conversation (tenant_id);
CREATE INDEX idx_conversation_customer_id ON conversation (customer_id);
CREATE INDEX idx_conversation_tenant_status ON conversation (tenant_id, status);

-- ADR-013's precondition made structurally impossible to violate: a
-- customer has at most one non-closed conversation at a time.
CREATE UNIQUE INDEX uq_customer_active_conversation ON conversation (customer_id) WHERE status IN ('OPEN', 'ASSIGNED');

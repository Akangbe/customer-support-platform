CREATE TABLE customer (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenant (id),
    phone       TEXT NOT NULL,
    name        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_customer_tenant_phone UNIQUE (tenant_id, phone)
);

CREATE INDEX idx_customer_tenant_id ON customer (tenant_id);

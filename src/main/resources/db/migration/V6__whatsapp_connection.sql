CREATE TABLE whatsapp_connection (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL UNIQUE REFERENCES tenant (id),
    phone_number_id   VARCHAR(255) NOT NULL UNIQUE,
    waba_id           VARCHAR(255) NOT NULL,
    access_token      TEXT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- phone_number_id is deliberately NOT tenant-scoped: it's the join key
-- Meta gives us to resolve which tenant an inbound webhook belongs to
-- (whatsapp-domain.md §5) — the unique constraint above is the lookup index.

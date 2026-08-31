-- The per-tenant allowlist of WhatsApp message templates. Meta approves
-- templates against a specific WABA, so a template a tenant has not had
-- approved on their own WABA is not sendable on their number -- this table
-- is what lets us reject that at our edge with a clear error instead of
-- relaying a doomed call and surfacing Meta's error code to the caller.
--
-- Deliberately NOT a mirror of Meta's template catalogue. It stores only
-- what the send path has to decide on: does this tenant have this template,
-- and is it approved right now. Body/format/example content stays in
-- Business Manager, which remains the authority (Rule 1 -- keep Meta
-- identifiers at the edge).
CREATE TABLE whatsapp_template (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenant (id),
    name        VARCHAR(255) NOT NULL,
    status      VARCHAR(20) NOT NULL CHECK (status IN ('APPROVED', 'PENDING', 'REJECTED', 'PAUSED', 'DISABLED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Keyed on (tenant_id, name), not (tenant_id, name, language): a template
-- name is approved as a unit and its translations share that approval, so
-- one row per template per tenant is the whole decision. Registering the
-- same name twice for a tenant is an update, not a second row.
CREATE UNIQUE INDEX uq_whatsapp_template_tenant_name ON whatsapp_template (tenant_id, name);

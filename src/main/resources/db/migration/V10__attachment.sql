CREATE TABLE attachment (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenant (id),
    message_id    UUID REFERENCES message (id),
    object_key    VARCHAR(500) NOT NULL,
    content_type  VARCHAR(255) NOT NULL,
    file_name     VARCHAR(255),
    size_bytes    BIGINT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_attachment_tenant_id ON attachment (tenant_id);

-- WhatsApp's Cloud API model gives each media message exactly one media
-- object — never a join table for many-to-one (storage-domain.md §2).
CREATE UNIQUE INDEX uq_attachment_message_id ON attachment (message_id) WHERE message_id IS NOT NULL;

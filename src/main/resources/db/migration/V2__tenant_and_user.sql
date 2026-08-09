CREATE TABLE tenant (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL,
    slug        TEXT NOT NULL,
    status      VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_slug UNIQUE (slug)
);

-- "user" is a reserved SQL keyword; the table is app_user, the entity is still User.
CREATE TABLE app_user (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID NOT NULL REFERENCES tenant (id),
    email                    TEXT NOT NULL,
    password_hash            TEXT,
    name                     TEXT NOT NULL,
    role                     VARCHAR(20) NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MANAGER', 'AGENT')),
    status                   VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'DISABLED')),
    invite_token             TEXT,
    invite_token_expires_at  TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at            TIMESTAMPTZ,
    CONSTRAINT uq_app_user_email UNIQUE (email),
    CONSTRAINT uq_app_user_invite_token UNIQUE (invite_token)
);

CREATE INDEX idx_app_user_tenant_id ON app_user (tenant_id);

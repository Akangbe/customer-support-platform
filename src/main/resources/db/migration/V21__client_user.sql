-- A person at a client business who signs in to read that client's own data.
-- They live in app_user so they share login, lockout and invites, but they
-- are tied to one client account and are not members of the tenant's team.
ALTER TABLE app_user ADD COLUMN client_account_id UUID;

ALTER TABLE app_user ADD CONSTRAINT fk_app_user_client_account
    FOREIGN KEY (client_account_id, tenant_id) REFERENCES client_account (id, tenant_id);

CREATE INDEX idx_app_user_client_account ON app_user (client_account_id)
    WHERE client_account_id IS NOT NULL;

ALTER TABLE app_user DROP CONSTRAINT app_user_role_check;
ALTER TABLE app_user ADD CONSTRAINT app_user_role_check
    CHECK (role IN ('OWNER', 'ADMIN', 'MANAGER', 'AGENT', 'CLIENT'));

-- Both directions: a CLIENT without an account would have no scope to read
-- from, and a staff role with one would blur who is on which side.
ALTER TABLE app_user ADD CONSTRAINT chk_app_user_client_account
    CHECK ((role = 'CLIENT') = (client_account_id IS NOT NULL));

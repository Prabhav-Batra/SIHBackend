-- V2 — the five identity tables of §8.2–§8.6.
--
-- Deliberately excluded here: users.institution_id has no foreign key yet, because
-- `institutions` is created in B3. The FK is added by that migration. The column is
-- present now so that no later migration has to rewrite the users table.

CREATE EXTENSION IF NOT EXISTS citext;

-- ── roles ────────────────────────────────────────────────────────────────────
-- Fixed UUIDs. Role identifiers appear in CHECK constraints (see users below) and in
-- later seed migrations; generating them would make those references unwritable.
CREATE TABLE roles (
    id           uuid PRIMARY KEY,
    name         text NOT NULL,
    display_name text NOT NULL,
    description  text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_roles_name UNIQUE (name),
    CONSTRAINT ck_roles_name CHECK (name IN (
        'SYSTEM_ADMIN', 'PRINCIPAL_INVESTIGATOR', 'TRIAL_COORDINATOR', 'RESEARCH_STAFF',
        'ETHICS_MEMBER', 'SAFETY_OFFICER', 'REGULATORY_OFFICER'))
);

-- ── permissions ──────────────────────────────────────────────────────────────
CREATE TABLE permissions (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text NOT NULL,
    resource    text NOT NULL,
    action      text NOT NULL,
    description text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_permissions_name UNIQUE (name),
    -- §6.3: every permission is exactly resource:action. A name that does not decompose
    -- that way cannot be enforced by require_permission and must not be storable.
    CONSTRAINT ck_permissions_name_shape CHECK (name = resource || ':' || action)
);
CREATE INDEX ix_permissions_resource ON permissions (resource);

-- ── role_permissions ─────────────────────────────────────────────────────────
-- Composite PK, not a surrogate: a grant is present or absent, never duplicated (§8.5).
CREATE TABLE role_permissions (
    role_id       uuid NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_id uuid NOT NULL REFERENCES permissions (id) ON DELETE CASCADE,
    granted_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, permission_id)
);
CREATE INDEX ix_role_permissions_permission ON role_permissions (permission_id);

-- ── users ────────────────────────────────────────────────────────────────────
CREATE TABLE users (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email               citext NOT NULL,
    password_hash       text NOT NULL,
    full_name           text NOT NULL,
    role_id             uuid NOT NULL REFERENCES roles (id) ON DELETE RESTRICT,
    institution_id      uuid,                       -- FK added in B3 with `institutions`
    status              text NOT NULL DEFAULT 'ACTIVE',
    mfa_secret          text,
    mfa_enabled         boolean NOT NULL DEFAULT false,
    failed_login_count  integer NOT NULL DEFAULT 0,
    last_login_at       timestamptz,
    password_changed_at timestamptz NOT NULL DEFAULT now(),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'LOCKED')),
    CONSTRAINT ck_users_failed_login_count CHECK (failed_login_count >= 0),
    -- §8.2: an active ethics member without an institution has no computable review
    -- scope (§5.5), so the database refuses to store one.
    CONSTRAINT ck_users_ethics_needs_institution CHECK (
        status <> 'ACTIVE'
        OR role_id <> '00000000-0000-0000-0000-000000000005'::uuid
        OR institution_id IS NOT NULL)
);
CREATE INDEX ix_users_institution_role ON users (institution_id, role_id);

-- ── sessions ─────────────────────────────────────────────────────────────────
-- §8.6: server-side refresh state. Without it, logout and revocation are impossible —
-- a signed JWT is valid until it expires and a server with no record cannot revoke it.
CREATE TABLE sessions (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),  -- also the refresh jti
    user_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id      uuid NOT NULL,
    token_hash     text NOT NULL,       -- SHA-256; the token itself is never stored
    issued_at      timestamptz NOT NULL DEFAULT now(),
    expires_at     timestamptz NOT NULL,
    revoked_at     timestamptz,
    revoked_reason text,
    ip_address     inet,
    user_agent     text,
    CONSTRAINT uq_sessions_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_sessions_revoked_reason CHECK (revoked_reason IS NULL OR revoked_reason IN (
        'LOGOUT', 'ROTATED', 'REUSE_DETECTED', 'ADMIN_REVOKE', 'PASSWORD_CHANGE')),
    -- A revocation without a reason is unauditable; a reason without a revocation is a lie.
    CONSTRAINT ck_sessions_revocation_paired CHECK (
        (revoked_at IS NULL) = (revoked_reason IS NULL)),
    CONSTRAINT ck_sessions_expiry_after_issue CHECK (expires_at > issued_at)
);
CREATE INDEX ix_sessions_user_active ON sessions (user_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_sessions_family ON sessions (family_id);
CREATE INDEX ix_sessions_expires ON sessions (expires_at);

-- ── updated_at maintenance ───────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

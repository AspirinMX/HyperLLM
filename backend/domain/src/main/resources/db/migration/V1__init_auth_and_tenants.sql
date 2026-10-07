-- Phase 1 (Part 1): Authentication, Dual Control-Plane Users, Multi-Tenancy & API Keys

CREATE TABLE users (
    id                 UUID PRIMARY KEY,
    email              VARCHAR(320) NOT NULL,
    email_verified_at  TIMESTAMPTZ NULL,
    display_name       VARCHAR(120) NOT NULL,
    avatar_url         VARCHAR(512) NULL,
    locale             VARCHAR(16) NOT NULL DEFAULT 'en-US',
    preferred_currency VARCHAR(8) NOT NULL DEFAULT 'USD',
    default_org_id     UUID NULL,
    status             VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    last_login_at      TIMESTAMPTZ NULL,
    deleted_at         TIMESTAMPTZ NULL,
    version            BIGINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_users_email_active
    ON users (lower(email))
    WHERE deleted_at IS NULL;

CREATE INDEX idx_users_default_org
    ON users (default_org_id)
    WHERE default_org_id IS NOT NULL;

CREATE TABLE user_identities (
    id                UUID PRIMARY KEY,
    user_id           UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    provider          VARCHAR(32) NOT NULL
        CHECK (provider IN ('PASSWORD', 'GITHUB', 'GOOGLE', 'SSO_OIDC', 'SSO_SAML')),
    provider_subject  VARCHAR(255) NOT NULL,
    sso_connection_id UUID NULL, -- FK added in Phase 3 (V5) once sso_connections exists
    password_hash     VARCHAR(255) NULL,
    provider_email    VARCHAR(320) NULL,
    metadata_json     JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_used_at      TIMESTAMPTZ NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_user_identities_global_subject
    ON user_identities (provider, provider_subject)
    WHERE sso_connection_id IS NULL;

CREATE UNIQUE INDEX uk_user_identities_sso_subject
    ON user_identities (sso_connection_id, provider_subject)
    WHERE sso_connection_id IS NOT NULL;

CREATE INDEX idx_user_identities_user
    ON user_identities (user_id);

CREATE TABLE refresh_tokens (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    token_family_id UUID NOT NULL,
    token_hash      VARCHAR(128) NOT NULL UNIQUE,
    client_ip       INET NULL,
    user_agent      VARCHAR(512) NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    rotated_at      TIMESTAMPTZ NULL,
    revoked_at      TIMESTAMPTZ NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user_revoked
    ON refresh_tokens (user_id, revoked_at);

CREATE INDEX idx_refresh_tokens_family
    ON refresh_tokens (token_family_id);

CREATE INDEX idx_refresh_tokens_expires
    ON refresh_tokens (expires_at);

CREATE TABLE admin_users (
    id                       UUID PRIMARY KEY,
    username                 VARCHAR(64) NOT NULL,
    email                    VARCHAR(320) NOT NULL,
    password_hash            VARCHAR(255) NOT NULL,
    role                     VARCHAR(32) NOT NULL
        CHECK (role IN ('SUPER_ADMIN', 'OPS', 'FINANCE', 'AUDITOR')),
    totp_secret_encrypted    BYTEA NULL,
    mfa_enabled              BOOLEAN NOT NULL DEFAULT FALSE,
    recovery_codes_hash_json JSONB NULL,
    allowed_cidrs            CIDR[] NULL,
    status                   VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    last_login_at            TIMESTAMPTZ NULL,
    deleted_at               TIMESTAMPTZ NULL,
    version                  BIGINT NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_admin_users_username_active
    ON admin_users (lower(username))
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX uk_admin_users_email_active
    ON admin_users (lower(email))
    WHERE deleted_at IS NULL;

CREATE TABLE admin_sessions (
    id              UUID PRIMARY KEY,
    admin_user_id   UUID NOT NULL REFERENCES admin_users (id) ON DELETE RESTRICT,
    token_hash      VARCHAR(128) NOT NULL UNIQUE,
    mfa_verified_at TIMESTAMPTZ NULL,
    client_ip       INET NOT NULL,
    user_agent      VARCHAR(512) NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_admin_sessions_admin_revoked
    ON admin_sessions (admin_user_id, revoked_at);

CREATE TABLE customer_tiers (
    id                      UUID PRIMARY KEY,
    tier_code               VARCHAR(32) NOT NULL,
    display_name            VARCHAR(64) NOT NULL,
    default_discount_bps    INTEGER NOT NULL DEFAULT 10000
        CHECK (default_discount_bps > 0 AND default_discount_bps <= 20000),
    default_org_concurrency INTEGER NOT NULL DEFAULT 50
        CHECK (default_org_concurrency > 0),
    description             TEXT NULL,
    deleted_at              TIMESTAMPTZ NULL,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_customer_tiers_code_active
    ON customer_tiers (tier_code)
    WHERE deleted_at IS NULL;

INSERT INTO customer_tiers (id, tier_code, display_name, default_discount_bps, default_org_concurrency, description)
VALUES ('01900000-0000-7000-8000-000000000001', 'STANDARD', 'Standard', 10000, 50, 'Default standard customer tier');

CREATE TABLE organizations (
    id                 UUID PRIMARY KEY,
    name               VARCHAR(120) NOT NULL,
    slug               VARCHAR(80) NOT NULL,
    owner_user_id      UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    tier_id            UUID NOT NULL REFERENCES customer_tiers (id) ON DELETE RESTRICT,
    discount_ratio_bps INTEGER NULL
        CHECK (discount_ratio_bps IS NULL OR (discount_ratio_bps > 0 AND discount_ratio_bps <= 20000)),
    max_concurrency    INTEGER NOT NULL DEFAULT 50
        CHECK (max_concurrency > 0),
    is_personal        BOOLEAN NOT NULL DEFAULT FALSE,
    status             VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    deleted_at         TIMESTAMPTZ NULL,
    version            BIGINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_organizations_slug_active
    ON organizations (lower(slug))
    WHERE deleted_at IS NULL;

CREATE INDEX idx_organizations_owner
    ON organizations (owner_user_id);

CREATE TABLE org_members (
    id         UUID PRIMARY KEY,
    org_id     UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    user_id    UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    role       VARCHAR(32) NOT NULL
        CHECK (role IN ('OWNER', 'ADMIN', 'DEVELOPER', 'BILLING')),
    joined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_org_members_org_user UNIQUE (org_id, user_id)
);

CREATE INDEX idx_org_members_user
    ON org_members (user_id);

CREATE TABLE org_invitations (
    id                 UUID PRIMARY KEY,
    org_id             UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    email              VARCHAR(320) NOT NULL,
    role               VARCHAR(32) NOT NULL
        CHECK (role IN ('ADMIN', 'DEVELOPER', 'BILLING')),
    project_ids        UUID[] NOT NULL DEFAULT '{}',
    token_hash         VARCHAR(128) NOT NULL UNIQUE,
    invited_by_user_id UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    expires_at         TIMESTAMPTZ NOT NULL,
    accepted_at        TIMESTAMPTZ NULL,
    status             VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACCEPTED', 'EXPIRED', 'REVOKED')),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_org_invitations_org_status
    ON org_invitations (org_id, status);

CREATE TABLE projects (
    id                      UUID PRIMARY KEY,
    org_id                  UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    name                    VARCHAR(120) NOT NULL,
    environment             VARCHAR(32) NOT NULL DEFAULT 'PRODUCTION'
        CHECK (environment IN ('PRODUCTION', 'STAGING', 'DEVELOPMENT')),
    monthly_hard_cap_micros BIGINT NULL
        CHECK (monthly_hard_cap_micros IS NULL OR monthly_hard_cap_micros >= 0),
    monthly_soft_cap_micros BIGINT NULL
        CHECK (monthly_soft_cap_micros IS NULL OR monthly_soft_cap_micros >= 0),
    allowed_models          TEXT[] NULL,
    rpm_limit               INTEGER NULL CHECK (rpm_limit IS NULL OR rpm_limit > 0),
    tpm_limit               INTEGER NULL CHECK (tpm_limit IS NULL OR tpm_limit > 0),
    max_concurrency         INTEGER NULL CHECK (max_concurrency IS NULL OR max_concurrency > 0),
    zdr_debug_enabled       BOOLEAN NOT NULL DEFAULT FALSE,
    zdr_debug_ttl_days      SMALLINT NOT NULL DEFAULT 3
        CHECK (zdr_debug_ttl_days BETWEEN 1 AND 7),
    guardrail_policy_id     UUID NULL, -- FK added in Phase 3 (V5) once guardrail_policies exists
    status                  VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'PAUSED', 'ARCHIVED')),
    deleted_at              TIMESTAMPTZ NULL,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_projects_org_name_active
    ON projects (org_id, lower(name))
    WHERE deleted_at IS NULL;

CREATE TABLE project_members (
    id                 UUID PRIMARY KEY,
    project_id         UUID NOT NULL REFERENCES projects (id) ON DELETE RESTRICT,
    org_id             UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    user_id            UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    granted_by_user_id UUID NULL REFERENCES users (id) ON DELETE RESTRICT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_project_members_project_user UNIQUE (project_id, user_id)
);

CREATE INDEX idx_project_members_org_user
    ON project_members (org_id, user_id);

CREATE TABLE api_keys (
    id                 UUID PRIMARY KEY,
    project_id         UUID NOT NULL REFERENCES projects (id) ON DELETE RESTRICT,
    org_id             UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    created_by_user_id UUID NULL REFERENCES users (id) ON DELETE RESTRICT,
    name               VARCHAR(120) NOT NULL,
    key_prefix         VARCHAR(24) NOT NULL,
    key_hash           VARCHAR(128) NOT NULL UNIQUE,
    pepper_version     SMALLINT NOT NULL DEFAULT 1,
    allowed_models     TEXT[] NULL,
    ip_whitelist       CIDR[] NULL,
    rpm_limit          INTEGER NULL CHECK (rpm_limit IS NULL OR rpm_limit > 0),
    tpm_limit          INTEGER NULL CHECK (tpm_limit IS NULL OR tpm_limit > 0),
    max_concurrency    INTEGER NULL CHECK (max_concurrency IS NULL OR max_concurrency > 0),
    quota_limit_micros BIGINT NULL CHECK (quota_limit_micros IS NULL OR quota_limit_micros >= 0),
    expires_at         TIMESTAMPTZ NULL,
    last_used_at       TIMESTAMPTZ NULL,
    status             VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED', 'REVOKED')),
    deleted_at         TIMESTAMPTZ NULL,
    version            BIGINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_api_keys_project_status_active
    ON api_keys (project_id, status)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_api_keys_org
    ON api_keys (org_id);

-- Phase 1 (Part 1): Wallets, Monthly Partitioned Wallet Ledger & Audit Logs

CREATE TABLE wallets (
    org_id                       UUID PRIMARY KEY REFERENCES organizations (id) ON DELETE RESTRICT,
    cash_balance_micros          BIGINT NOT NULL DEFAULT 0 CHECK (cash_balance_micros >= 0),
    bonus_balance_micros         BIGINT NOT NULL DEFAULT 0 CHECK (bonus_balance_micros >= 0),
    total_recharged_micros       BIGINT NOT NULL DEFAULT 0 CHECK (total_recharged_micros >= 0),
    total_bonus_granted_micros   BIGINT NOT NULL DEFAULT 0 CHECK (total_bonus_granted_micros >= 0),
    total_consumed_cash_micros   BIGINT NOT NULL DEFAULT 0 CHECK (total_consumed_cash_micros >= 0),
    total_consumed_bonus_micros  BIGINT NOT NULL DEFAULT 0 CHECK (total_consumed_bonus_micros >= 0),
    low_balance_threshold_micros BIGINT NULL
        CHECK (low_balance_threshold_micros IS NULL OR low_balance_threshold_micros >= 0),
    version                      BIGINT NOT NULL DEFAULT 0,
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION ensure_monthly_partition(p_parent_table TEXT, p_month_start TIMESTAMPTZ)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_utc_month TIMESTAMP;
    v_from      TIMESTAMPTZ;
    v_to        TIMESTAMPTZ;
    v_part_name TEXT;
BEGIN
    v_utc_month := date_trunc('month', p_month_start AT TIME ZONE 'UTC');
    v_from      := v_utc_month AT TIME ZONE 'UTC';
    v_to        := (v_utc_month + INTERVAL '1 month') AT TIME ZONE 'UTC';
    v_part_name := format('%s_%s', p_parent_table, to_char(v_utc_month, 'YYYY_MM'));

    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
        v_part_name,
        p_parent_table,
        v_from,
        v_to
    );
END;
$$;

CREATE TABLE wallet_ledger (
    id                         UUID NOT NULL,
    org_id                     UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    project_id                 UUID NULL REFERENCES projects (id) ON DELETE RESTRICT,
    api_key_id                 UUID NULL REFERENCES api_keys (id) ON DELETE RESTRICT,
    ledger_type                VARCHAR(32) NOT NULL
        CHECK (ledger_type IN ('RECHARGE', 'CONSUME', 'BONUS_GRANT', 'BONUS_EXPIRE', 'REFUND', 'ADJUST')),
    idempotency_key            VARCHAR(160) NOT NULL,
    cash_delta_micros          BIGINT NOT NULL DEFAULT 0,
    bonus_delta_micros         BIGINT NOT NULL DEFAULT 0,
    pack_delta_units           BIGINT NOT NULL DEFAULT 0,
    cash_balance_after_micros  BIGINT NULL,
    bonus_balance_after_micros BIGINT NULL,
    allocation_detail_json     JSONB NULL,
    ref_request_id             VARCHAR(64) NULL,
    ref_order_id               UUID NULL, -- FK added in Phase 2 (V4) once payment_orders exists
    ref_adjustment_id          UUID NULL, -- FK added in Phase 3 (V5) once wallet_adjustments exists
    description                VARCHAR(255) NULL,
    created_at                 TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (id, created_at),
    CONSTRAINT uk_wallet_ledger_idempotency UNIQUE (idempotency_key, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX idx_wallet_ledger_org_created
    ON wallet_ledger (org_id, created_at DESC);

CREATE INDEX idx_wallet_ledger_project_created
    ON wallet_ledger (project_id, created_at DESC)
    WHERE project_id IS NOT NULL;

CREATE INDEX idx_wallet_ledger_ref_request
    ON wallet_ledger (ref_request_id)
    WHERE ref_request_id IS NOT NULL;

CREATE TABLE audit_logs (
    id            UUID NOT NULL,
    actor_type    VARCHAR(32) NOT NULL
        CHECK (actor_type IN ('USER', 'ADMIN', 'SYSTEM')),
    actor_id      UUID NULL,
    org_id        UUID NULL,
    action        VARCHAR(80) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id   VARCHAR(64) NULL,
    diff_json     JSONB NULL,
    client_ip     INET NULL,
    trace_id      VARCHAR(64) NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX idx_audit_logs_org_created
    ON audit_logs (org_id, created_at DESC)
    WHERE org_id IS NOT NULL;

CREATE INDEX idx_audit_logs_actor_created
    ON audit_logs (actor_type, actor_id, created_at DESC);

-- Pre-create monthly partitions from -1 month to +12 months in UTC
DO $$
DECLARE
    v_base_utc TIMESTAMPTZ;
    v_offset   INTEGER;
BEGIN
    v_base_utc := date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC';
    FOR v_offset IN -1..12 LOOP
        PERFORM ensure_monthly_partition('wallet_ledger', v_base_utc + make_interval(months => v_offset));
        PERFORM ensure_monthly_partition('audit_logs', v_base_utc + make_interval(months => v_offset));
    END LOOP;
END;
$$;

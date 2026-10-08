-- Phase 2 (2.1): Commercial billing foundation, payment orders, entitlements, outbox & alerts.
-- See docs/design/04-01-v4-schema.md for the design rationale of every constraint.

-- ---------------------------------------------------------------------------
-- 1. payment_orders：在线充值与资源包购买订单（不可软删，用 status 表达终止）
-- ---------------------------------------------------------------------------
CREATE TABLE payment_orders (
    id                       UUID PRIMARY KEY,
    order_no                 VARCHAR(64) NOT NULL,
    org_id                   UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    created_by_user_id       UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    order_type               VARCHAR(32) NOT NULL
        CHECK (order_type IN ('WALLET_TOPUP', 'RESOURCE_PACK_PURCHASE')),
    payment_channel          VARCHAR(32) NOT NULL
        CHECK (payment_channel IN ('STRIPE', 'ALIPAY', 'WECHAT_PAY')),
    pay_currency             VARCHAR(8) NOT NULL CHECK (pay_currency IN ('USD', 'CNY')),
    pay_amount_minor         BIGINT NOT NULL CHECK (pay_amount_minor > 0),
    fx_rate                  NUMERIC(18, 8) NOT NULL CHECK (fx_rate > 0),
    credit_micros            BIGINT NOT NULL DEFAULT 0 CHECK (credit_micros >= 0),
    invoiceable_amount_minor BIGINT NOT NULL DEFAULT 0 CHECK (invoiceable_amount_minor >= 0),
    pack_spec_json           JSONB NULL,
    external_tx_id           VARCHAR(160) NULL,
    idempotency_key          VARCHAR(128) NOT NULL,
    status                   VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'CLOSED', 'REFUNDED')),
    paid_at                  TIMESTAMPTZ NULL,
    expires_at               TIMESTAMPTZ NOT NULL,
    version                  BIGINT NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_payment_orders_order_no UNIQUE (order_no),
    CONSTRAINT uk_payment_orders_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_payment_orders_paid_at
        CHECK (status <> 'PAID' OR paid_at IS NOT NULL)
);

CREATE UNIQUE INDEX uk_payment_orders_external_tx
    ON payment_orders (payment_channel, external_tx_id)
    WHERE external_tx_id IS NOT NULL;

CREATE INDEX idx_payment_orders_org_created
    ON payment_orders (org_id, created_at DESC);

-- 供 2.6 的关单扫描：WHERE status = 'PENDING' AND expires_at < now()
CREATE INDEX idx_payment_orders_pending_expiry
    ON payment_orders (expires_at)
    WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------
-- 2. resource_packs：第一顺位定向模型资源包
-- ---------------------------------------------------------------------------
CREATE TABLE resource_packs (
    id                UUID PRIMARY KEY,
    org_id            UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    pack_name         VARCHAR(120) NOT NULL,
    unit_type         VARCHAR(32) NOT NULL CHECK (unit_type IN ('TOKENS', 'CALLS')),
    applicable_models TEXT[] NOT NULL,
    total_units       BIGINT NOT NULL CHECK (total_units > 0),
    remaining_units   BIGINT NOT NULL
        CHECK (remaining_units >= 0 AND remaining_units <= total_units),
    purchase_order_id UUID NULL REFERENCES payment_orders (id) ON DELETE RESTRICT,
    expires_at        TIMESTAMPTZ NOT NULL,
    status            VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'EXHAUSTED', 'EXPIRED', 'REVOKED')),
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_resource_packs_models_non_empty
        CHECK (cardinality(applicable_models) > 0),
    CONSTRAINT chk_resource_packs_models_no_blank
        CHECK (array_position(applicable_models, '') IS NULL)
);

CREATE INDEX idx_resource_packs_org_status_expires
    ON resource_packs (org_id, status, expires_at);

CREATE INDEX idx_resource_packs_purchase_order
    ON resource_packs (purchase_order_id)
    WHERE purchase_order_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. vouchers：代金券/赠金模板
-- ---------------------------------------------------------------------------
CREATE TABLE vouchers (
    id                      UUID PRIMARY KEY,
    name                    VARCHAR(120) NOT NULL,
    voucher_type            VARCHAR(32) NOT NULL
        CHECK (voucher_type IN ('SIGNUP_BONUS', 'PROMO_CODE', 'CAMPAIGN')),
    amount_micros           BIGINT NOT NULL CHECK (amount_micros > 0),
    applicable_models       TEXT[] NULL,
    valid_days              INTEGER NULL CHECK (valid_days IS NULL OR valid_days > 0),
    fixed_expires_at        TIMESTAMPTZ NULL,
    max_redemptions_total   INTEGER NULL
        CHECK (max_redemptions_total IS NULL OR max_redemptions_total > 0),
    max_redemptions_per_org INTEGER NOT NULL DEFAULT 1 CHECK (max_redemptions_per_org > 0),
    status                  VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'PAUSED', 'ENDED')),
    created_by_admin_id     UUID NULL REFERENCES admin_users (id) ON DELETE RESTRICT,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_vouchers_expiry_mode
        CHECK (NOT (valid_days IS NOT NULL AND fixed_expires_at IS NOT NULL)),
    CONSTRAINT chk_vouchers_models_non_empty
        CHECK (applicable_models IS NULL OR cardinality(applicable_models) > 0),
    CONSTRAINT chk_vouchers_models_no_blank
        CHECK (applicable_models IS NULL OR array_position(applicable_models, '') IS NULL)
);

-- ---------------------------------------------------------------------------
-- 4. redemption_codes：批量兑换码池（只追加 + 一次状态迁移）
-- ---------------------------------------------------------------------------
CREATE TABLE redemption_codes (
    id                   UUID PRIMARY KEY,
    voucher_id           UUID NOT NULL REFERENCES vouchers (id) ON DELETE RESTRICT,
    code_hash            VARCHAR(128) NOT NULL,
    code_hint            VARCHAR(24) NULL,
    redeemed_org_id      UUID NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    redeemed_by_user_id  UUID NULL REFERENCES users (id) ON DELETE RESTRICT,
    redeemed_at          TIMESTAMPTZ NULL,
    expires_at           TIMESTAMPTZ NULL,
    status               VARCHAR(32) NOT NULL DEFAULT 'UNUSED'
        CHECK (status IN ('UNUSED', 'REDEEMED', 'EXPIRED', 'VOIDED')),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_redemption_codes_hash UNIQUE (code_hash),
    CONSTRAINT chk_redemption_codes_redeemed_at
        CHECK (status <> 'REDEEMED' OR (redeemed_org_id IS NOT NULL AND redeemed_at IS NOT NULL))
);

CREATE INDEX idx_redemption_codes_voucher_status
    ON redemption_codes (voucher_id, status);

-- ---------------------------------------------------------------------------
-- 5. bonus_grants：第二顺位赠金批次
-- ---------------------------------------------------------------------------
CREATE TABLE bonus_grants (
    id                 UUID PRIMARY KEY,
    org_id             UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    grant_source       VARCHAR(32) NOT NULL
        CHECK (grant_source IN ('SIGNUP', 'VOUCHER', 'ADMIN_GRANT', 'COMPENSATION')),
    voucher_id         UUID NULL REFERENCES vouchers (id) ON DELETE RESTRICT,
    redemption_code_id UUID NULL REFERENCES redemption_codes (id) ON DELETE RESTRICT,
    applicable_models  TEXT[] NULL,
    initial_micros     BIGINT NOT NULL CHECK (initial_micros > 0),
    remaining_micros   BIGINT NOT NULL
        CHECK (remaining_micros >= 0 AND remaining_micros <= initial_micros),
    expires_at         TIMESTAMPTZ NULL,
    status             VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'EXHAUSTED', 'EXPIRED', 'REVOKED')),
    version            BIGINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_bonus_grants_models_non_empty
        CHECK (applicable_models IS NULL OR cardinality(applicable_models) > 0),
    CONSTRAINT chk_bonus_grants_models_no_blank
        CHECK (applicable_models IS NULL OR array_position(applicable_models, '') IS NULL)
);

CREATE INDEX idx_bonus_grants_org_status_expires
    ON bonus_grants (org_id, status, expires_at);

-- ---------------------------------------------------------------------------
-- 6. org_model_pricing_overrides：大客户单模型协议价（纳入 Config CDC）
-- ---------------------------------------------------------------------------
CREATE TABLE org_model_pricing_overrides (
    id                  UUID PRIMARY KEY,
    org_id              UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    virtual_model_id    UUID NOT NULL REFERENCES virtual_models (id) ON DELETE RESTRICT,
    override_mode       VARCHAR(32) NOT NULL
        CHECK (override_mode IN ('DISCOUNT_BPS', 'FIXED_PRICING')),
    discount_ratio_bps  INTEGER NULL
        CHECK (discount_ratio_bps IS NULL
               OR (discount_ratio_bps > 0 AND discount_ratio_bps <= 20000)),
    fixed_pricing_json  JSONB NULL,
    effective_from      TIMESTAMPTZ NOT NULL,
    effective_to        TIMESTAMPTZ NULL,
    status              VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_by_admin_id UUID NULL REFERENCES admin_users (id) ON DELETE RESTRICT,
    deleted_at          TIMESTAMPTZ NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_org_override_window
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT chk_org_override_payload
        CHECK ((override_mode = 'DISCOUNT_BPS'
                    AND discount_ratio_bps IS NOT NULL AND fixed_pricing_json IS NULL)
            OR (override_mode = 'FIXED_PRICING'
                    AND fixed_pricing_json IS NOT NULL AND discount_ratio_bps IS NULL))
);

CREATE INDEX idx_org_override_lookup
    ON org_model_pricing_overrides (org_id, virtual_model_id, effective_from DESC)
    WHERE deleted_at IS NULL AND status = 'ACTIVE';

-- 时间窗不重叠由 2.2 的应用层校验（与 pricing_rules 一致），不在 DB 层用 EXCLUDE 约束。

-- ---------------------------------------------------------------------------
-- 7. notification_endpoints：告警通知端点（消费方为 2.9）
-- ---------------------------------------------------------------------------
CREATE TABLE notification_endpoints (
    id                       UUID PRIMARY KEY,
    org_id                   UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    channel_type             VARCHAR(16) NOT NULL
        CHECK (channel_type IN ('EMAIL', 'WEBHOOK')),
    target                   VARCHAR(512) NOT NULL,
    encrypted_signing_secret BYTEA NULL,
    status                   VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    deleted_at               TIMESTAMPTZ NULL,
    version                  BIGINT NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_endpoints_org
    ON notification_endpoints (org_id, status)
    WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- 8. billing_alert_rules：低余额与预算告警规则（消费方为 2.9）
-- ---------------------------------------------------------------------------
CREATE TABLE billing_alert_rules (
    id                UUID PRIMARY KEY,
    org_id            UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    project_id        UUID NULL REFERENCES projects (id) ON DELETE RESTRICT,
    alert_type        VARCHAR(32) NOT NULL
        CHECK (alert_type IN ('LOW_BALANCE', 'PROJECT_SOFT_CAP', 'PROJECT_HARD_CAP')),
    threshold_micros  BIGINT NOT NULL CHECK (threshold_micros >= 0),
    endpoint_ids      UUID[] NOT NULL,
    cooldown_minutes  INTEGER NOT NULL DEFAULT 60 CHECK (cooldown_minutes > 0),
    last_triggered_at TIMESTAMPTZ NULL,
    status            VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    deleted_at        TIMESTAMPTZ NULL,
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_billing_alert_rules_endpoints
        CHECK (cardinality(endpoint_ids) > 0)
);

CREATE INDEX idx_billing_alert_rules_org
    ON billing_alert_rules (org_id, alert_type)
    WHERE deleted_at IS NULL;

-- endpoint_ids 是 UUID[]，无法建外键；引用完整性由 2.9 服务层保证
--（删除 endpoint 前必须检查是否被规则引用）。

-- ---------------------------------------------------------------------------
-- 9. outbox_events：Debezium Transactional Outbox（不分区，见 D8）
-- ---------------------------------------------------------------------------
CREATE TABLE outbox_events (
    id            UUID PRIMARY KEY,
    aggregatetype VARCHAR(64) NOT NULL,
    aggregateid   VARCHAR(64) NOT NULL,
    type          VARCHAR(64) NOT NULL,
    payload       JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 供 2.4 的 purge：DELETE FROM outbox_events WHERE created_at < now() - interval '7 days'
CREATE INDEX idx_outbox_events_created
    ON outbox_events (created_at);

-- aggregatetype 不加 CHECK：路由后缀由 Java 侧 OutboxAggregateType 约束，
-- DB 层保留扩展空间，避免每加一个业务 Topic 就要新迁移。

-- ---------------------------------------------------------------------------
-- 10. wallet_ledger：补齐 Phase 1 的外键与索引（不改 V3 本身）
-- ---------------------------------------------------------------------------
ALTER TABLE wallet_ledger
    ADD CONSTRAINT fk_wallet_ledger_ref_order
    FOREIGN KEY (ref_order_id) REFERENCES payment_orders (id) ON DELETE RESTRICT;

CREATE INDEX idx_wallet_ledger_ref_order
    ON wallet_ledger (ref_order_id)
    WHERE ref_order_id IS NOT NULL;

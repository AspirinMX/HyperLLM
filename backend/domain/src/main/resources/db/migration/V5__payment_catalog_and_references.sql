-- Phase 2 (2.7): the resource pack catalog, the payment references an order keeps, the durable
-- identity of a channel notification, and the redemption identities.
--
-- The order row is the local source of truth: the channel reference and the instruction it returned
-- are stored so a retried create returns the same payment surface, and the fulfillment event id is
-- the grant the confirmation transaction authorized.

CREATE TABLE resource_pack_skus (
    id                  UUID PRIMARY KEY,
    code                VARCHAR(64) NOT NULL,
    name                VARCHAR(120) NOT NULL,
    unit_type           VARCHAR(32) NOT NULL CHECK (unit_type IN ('TOKENS', 'CALLS')),
    applicable_models   TEXT[] NOT NULL,
    total_units         BIGINT NOT NULL CHECK (total_units > 0),
    price_micros        BIGINT NOT NULL CHECK (price_micros > 0),
    valid_days          INTEGER NOT NULL CHECK (valid_days > 0),
    status              VARCHAR(32) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    created_by_admin_id UUID NULL REFERENCES admin_users (id) ON DELETE RESTRICT,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_resource_pack_skus_models_non_empty
        CHECK (cardinality(applicable_models) > 0),
    CONSTRAINT chk_resource_pack_skus_models_no_blank
        CHECK (array_position(applicable_models, '') IS NULL)
);

CREATE UNIQUE INDEX uk_resource_pack_skus_code
    ON resource_pack_skus (lower(code));

CREATE INDEX idx_resource_pack_skus_active
    ON resource_pack_skus (status)
    WHERE status = 'ACTIVE';

ALTER TABLE payment_orders
    ADD COLUMN pack_sku_id         UUID NULL REFERENCES resource_pack_skus (id) ON DELETE RESTRICT,
    ADD COLUMN request_fingerprint VARCHAR(64) NULL,
    ADD COLUMN channel_ref         VARCHAR(160) NULL,
    ADD COLUMN channel_payload     JSONB NULL,
    ADD COLUMN fulfillment_event_id UUID NULL,
    ADD COLUMN fulfilled_at        TIMESTAMPTZ NULL,
    ADD COLUMN closed_at           TIMESTAMPTZ NULL,
    ADD COLUMN failure_code        VARCHAR(64) NULL,
    ADD COLUMN failure_detail      VARCHAR(500) NULL,
    ADD COLUMN refunded_at         TIMESTAMPTZ NULL,
    ADD COLUMN refunded_amount_minor BIGINT NOT NULL DEFAULT 0
        CHECK (refunded_amount_minor >= 0);

ALTER TABLE payment_orders
    ADD CONSTRAINT chk_payment_orders_pack_sku
        CHECK ((order_type = 'RESOURCE_PACK_PURCHASE') = (pack_sku_id IS NOT NULL)),
    ADD CONSTRAINT chk_payment_orders_fulfilled
        CHECK (fulfilled_at IS NULL OR status = 'PAID'),
    ADD CONSTRAINT chk_payment_orders_closed
        CHECK (closed_at IS NULL OR status IN ('CLOSED', 'FAILED')),
    ADD CONSTRAINT chk_payment_orders_refund
        CHECK (refunded_at IS NULL OR status = 'REFUNDED');

CREATE INDEX idx_payment_orders_pending_channel
    ON payment_orders (payment_channel, expires_at)
    WHERE status = 'PENDING';

-- One row per verified notification. The unique identity is what stops two concurrent deliveries of
-- the same channel event from both being treated as new; the order transition is the second gate.
CREATE TABLE payment_notifications (
    id              UUID PRIMARY KEY,
    payment_channel VARCHAR(32) NOT NULL
        CHECK (payment_channel IN ('STRIPE', 'ALIPAY', 'WECHAT_PAY')),
    notification_id VARCHAR(160) NOT NULL,
    order_no        VARCHAR(64) NULL,
    external_tx_id  VARCHAR(160) NULL,
    outcome         VARCHAR(32) NOT NULL
        CHECK (outcome IN ('FULFILLED', 'DUPLICATE', 'MISMATCH', 'REJECTED', 'ERROR')),
    detail_json     JSONB NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ NULL,
    CONSTRAINT uk_payment_notifications_identity UNIQUE (payment_channel, notification_id)
);

CREATE INDEX idx_payment_notifications_order
    ON payment_notifications (order_no)
    WHERE order_no IS NOT NULL;

-- Evidence that an operator has to look at: a verified-looking payment that does not match its
-- order, or a channel conflict. It never blocks the order path; it is written beside it.
CREATE TABLE payment_anomalies (
    id                  UUID PRIMARY KEY,
    org_id              UUID NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    order_id            UUID NULL REFERENCES payment_orders (id) ON DELETE RESTRICT,
    payment_channel     VARCHAR(32) NOT NULL
        CHECK (payment_channel IN ('STRIPE', 'ALIPAY', 'WECHAT_PAY')),
    kind                VARCHAR(48) NOT NULL
        CHECK (kind IN ('SIGNATURE', 'AMOUNT_MISMATCH', 'CURRENCY_MISMATCH', 'ORDER_MISMATCH',
                        'TRANSACTION_CONFLICT', 'UNMATCHED_PAYMENT', 'CHANNEL_CONFLICT')),
    detail_json         JSONB NULL,
    status              VARCHAR(32) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'RESOLVED', 'IGNORED')),
    resolved_by_admin_id UUID NULL REFERENCES admin_users (id) ON DELETE RESTRICT,
    resolved_at         TIMESTAMPTZ NULL,
    resolution          VARCHAR(500) NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_anomalies_open
    ON payment_anomalies (created_at DESC)
    WHERE status = 'OPEN';

CREATE INDEX idx_payment_anomalies_order
    ON payment_anomalies (order_id)
    WHERE order_id IS NOT NULL;

CREATE TABLE voucher_code_batches (
    id                  UUID PRIMARY KEY,
    voucher_id          UUID NOT NULL REFERENCES vouchers (id) ON DELETE RESTRICT,
    quantity            INTEGER NOT NULL CHECK (quantity > 0 AND quantity <= 10000),
    created_by_admin_id UUID NULL REFERENCES admin_users (id) ON DELETE RESTRICT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE redemption_codes
    ADD COLUMN batch_id       UUID NULL REFERENCES voucher_code_batches (id) ON DELETE RESTRICT,
    ADD COLUMN grant_event_id UUID NULL;

CREATE INDEX idx_redemption_codes_batch
    ON redemption_codes (batch_id)
    WHERE batch_id IS NOT NULL;

ALTER TABLE resource_packs
    ADD COLUMN source_sku_id UUID NULL REFERENCES resource_pack_skus (id) ON DELETE RESTRICT;

COMMENT ON COLUMN payment_orders.request_fingerprint IS
    'SHA-256 of the normalized create request; a replay with different terms is a conflict.';
COMMENT ON COLUMN payment_orders.fulfillment_event_id IS
    'The RECHARGE or PACK_GRANT event authorized in the confirmation transaction.';
COMMENT ON COLUMN redemption_codes.grant_event_id IS
    'The BONUS_GRANT event authorized when the code was redeemed.';

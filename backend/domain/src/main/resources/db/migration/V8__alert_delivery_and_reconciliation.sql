-- Phase 2 (2.10): durable alert deliveries and the reconciliation findings book.
--
-- A trigger is a decision, not a message: the sampler locks the rule, checks the cooldown and writes
-- one delivery per endpoint. The unique key makes a replayed trigger idempotent, and the lock column
-- lets several admin instances claim disjoint work.

CREATE TABLE notification_deliveries (
    id                 UUID PRIMARY KEY,
    rule_id            UUID NOT NULL REFERENCES billing_alert_rules (id) ON DELETE RESTRICT,
    trigger_id         UUID NOT NULL,
    org_id             UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    endpoint_id        UUID NOT NULL REFERENCES notification_endpoints (id) ON DELETE RESTRICT,
    alert_type         VARCHAR(32) NOT NULL
        CHECK (alert_type IN ('LOW_BALANCE', 'PROJECT_SOFT_CAP', 'PROJECT_HARD_CAP')),
    project_id         UUID NULL REFERENCES projects (id) ON DELETE RESTRICT,
    subject            VARCHAR(255) NOT NULL,
    payload_json       JSONB NOT NULL,
    status             VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENT', 'RETRY', 'DEAD')),
    attempts           INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until       TIMESTAMPTZ NULL,
    last_error_class   VARCHAR(64) NULL,
    last_error_detail  VARCHAR(512) NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at            TIMESTAMPTZ NULL,
    CONSTRAINT uk_notification_deliveries_trigger_endpoint UNIQUE (trigger_id, endpoint_id)
);

CREATE INDEX idx_notification_deliveries_due
    ON notification_deliveries (next_attempt_at)
    WHERE status IN ('PENDING', 'RETRY');

CREATE INDEX idx_notification_deliveries_status
    ON notification_deliveries (status, created_at DESC);

CREATE TABLE reconciliation_findings (
    id                UUID PRIMARY KEY,
    org_id            UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    check_kind        VARCHAR(32) NOT NULL
        CHECK (check_kind IN ('LEDGER_VS_WALLET', 'WALLET_VS_REDIS', 'BATCHES_VS_WALLET',
                              'SHORTFALL_EVIDENCE', 'PENDING_SETTLEMENT')),
    severity          VARCHAR(8) NOT NULL CHECK (severity IN ('INFO', 'WARN', 'P1')),
    expected_micros   BIGINT NULL,
    actual_micros     BIGINT NULL,
    delta_micros      BIGINT NULL,
    detail_json       JSONB NULL,
    state             VARCHAR(16) NOT NULL DEFAULT 'OPEN'
        CHECK (state IN ('OPEN', 'RESOLVED', 'IGNORED')),
    detected_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at       TIMESTAMPTZ NULL,
    resolution_note   VARCHAR(255) NULL
);

-- One open finding per organisation and check: repeated runs refresh it instead of piling up copies.
CREATE UNIQUE INDEX uk_reconciliation_findings_open
    ON reconciliation_findings (org_id, check_kind)
    WHERE state = 'OPEN';

CREATE INDEX idx_reconciliation_findings_state
    ON reconciliation_findings (state, severity, last_seen_at DESC);

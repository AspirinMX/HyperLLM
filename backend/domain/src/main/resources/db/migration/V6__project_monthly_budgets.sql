-- Phase 2 (2.8): the confirmed side of a project's monthly budget.
--
-- The gateway is authoritative for the month while its keys live; this table is the fold that
-- survives a Redis restart and the evidence a cold start uses to prove the month. The charge is
-- the rated value of the request, not the wallet delta: a pack or a grant paying for the call does
-- not make the call free, the project still consumed that much of its budget.

ALTER TABLE wallet_ledger
    ADD COLUMN budget_month         DATE NULL,
    ADD COLUMN budget_charge_micros BIGINT NOT NULL DEFAULT 0;

ALTER TABLE wallet_ledger
    ADD CONSTRAINT chk_wallet_ledger_budget_charge CHECK (budget_charge_micros >= 0);

-- The reconciliation and usage reports ask for one project and month at a time.
CREATE INDEX idx_wallet_ledger_project_budget
    ON wallet_ledger (project_id, budget_month)
    WHERE budget_month IS NOT NULL;

CREATE TABLE project_budget_months (
    project_id        UUID NOT NULL REFERENCES projects (id) ON DELETE RESTRICT,
    budget_month      DATE NOT NULL,
    confirmed_micros  BIGINT NOT NULL DEFAULT 0 CHECK (confirmed_micros >= 0),
    -- UNVERIFIED means the month is known to have a charge that could not be priced; the operator
    -- has to look before the month can be treated as complete again.
    state             VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED'
        CHECK (state IN ('CONFIRMED', 'UNVERIFIED')),
    first_enabled_at  TIMESTAMPTZ NULL,
    version           BIGINT NOT NULL DEFAULT 0,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, budget_month)
);

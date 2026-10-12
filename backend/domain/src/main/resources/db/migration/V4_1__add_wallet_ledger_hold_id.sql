-- Phase 2 (2.3): link wallet ledger rows back to the pre-hold that produced them.
-- Numbered V4_1 (not V6) so that Phase 3's reserved V5 keeps its position.
ALTER TABLE wallet_ledger
    ADD COLUMN hold_id UUID NULL;

CREATE INDEX idx_wallet_ledger_hold
    ON wallet_ledger (hold_id)
    WHERE hold_id IS NOT NULL;

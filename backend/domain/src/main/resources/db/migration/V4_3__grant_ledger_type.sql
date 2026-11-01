-- Phase 2 (2.7): a pack grant is its own ledger movement, and the batch a credit creates shares the
-- event identity.
--
-- The constraint is inherited by every partition; CASCADE drops those copies with the parent, and
-- adding it back on the parent recreates them (and covers partitions created later).
ALTER TABLE wallet_ledger
    DROP CONSTRAINT IF EXISTS wallet_ledger_ledger_type_check CASCADE;

ALTER TABLE wallet_ledger
    ADD CONSTRAINT wallet_ledger_ledger_type_check
    CHECK (ledger_type IN ('RECHARGE', 'CONSUME', 'BONUS_GRANT', 'PACK_GRANT', 'BONUS_EXPIRE', 'REFUND', 'ADJUST'));

COMMENT ON CONSTRAINT wallet_ledger_ledger_type_check ON wallet_ledger IS
    'PACK_GRANT was added in 2.7; a pack purchase is not an adjustment.';

COMMENT ON COLUMN bonus_grants.id IS
    'Identity of the BONUS_GRANT event that created the batch; a redelivery inserts the same id.';
COMMENT ON COLUMN resource_packs.id IS
    'Identity of the PACK_GRANT event that created the batch; the purchase order is the accounting reference.';
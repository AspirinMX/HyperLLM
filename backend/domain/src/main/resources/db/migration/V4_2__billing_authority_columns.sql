-- Phase 2 (2.4 to 2.6): the columns the settlement pipeline needs.
--
-- outbox_events becomes an append only transport table: no secondary index, no room reserved for
-- updates. Purging walks the UUIDv7 primary key range and is driven by the consumer watermark.
DROP INDEX IF EXISTS idx_outbox_events_created;

ALTER TABLE outbox_events SET (fillfactor = 100);

-- The ledger must be able to explain a charge that could not be covered, and a charge that was
-- applied after the pre-hold lease had already been reclaimed.
ALTER TABLE wallet_ledger
    ADD COLUMN shortfall_micros        BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN settled_after_reclaim   BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE wallet_ledger
    ADD CONSTRAINT chk_wallet_ledger_shortfall_non_negative CHECK (shortfall_micros >= 0);

-- The fold position of one organisation. It lives in PostgreSQL, not in Redis, so that "the ledger
-- is applied up to event X" advances in the same transaction that applies it; a Redis marker could
-- be committed while the ledger was not.
ALTER TABLE wallets
    ADD COLUMN last_applied_event_id UUID NULL;

-- Single row table: the consumers' delivery watermark drives the outbox purge. It is a table rather
-- than a Redis key because it must survive a Redis restart without going backwards.
CREATE TABLE outbox_watermark (
    id            BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id),
    last_event_id UUID NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO outbox_watermark (id, last_event_id) VALUES (TRUE, NULL);

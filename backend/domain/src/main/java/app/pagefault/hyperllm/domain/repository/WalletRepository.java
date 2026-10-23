package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.billing.Wallet;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository {

    void insert(Wallet wallet);

    Optional<Wallet> findByOrgId(UUID orgId);

    /** Cash and bonus of many organisations in one query, for list screens. */
    record Balance(long cashMicros, long bonusMicros) {
    }

    /**
     * Balances of the given organisations, keyed by organisation.
     *
     * <p>One statement for the whole page: a list must not turn into a query per row, and Redis is
     * the wrong source here because a list shows the ledger-confirmed value, not the live one.
     */
    java.util.Map<UUID, Balance> findBalancesByOrgIds(java.util.Collection<UUID> orgIds);

    boolean applyDelta(
            UUID orgId,
            long cashDeltaMicros,
            long bonusDeltaMicros,
            long rechargedDeltaMicros,
            long bonusGrantedDeltaMicros,
            long consumedCashDeltaMicros,
            long consumedBonusDeltaMicros,
            OffsetDateTime updatedAt
    );

    boolean applySettlementDelta(
            UUID orgId,
            long cashDeltaMicros,
            long bonusDeltaMicros,
            long consumedCashDeltaMicros,
            long consumedBonusDeltaMicros,
            OffsetDateTime updatedAt
    );

    /**
     * Applies one event's deltas and advances the fold position in the same statement.
     *
     * <p>Deliberately unclamped: a delta that would push a balance below zero is a defect in the
     * event, not something to swallow here, and the {@code CHECK} constraint is what surfaces it.
     * Clamping would break the identity between the ledger fold and this snapshot.
     */
    boolean applyEventDelta(
            UUID orgId,
            long cashDeltaMicros,
            long bonusDeltaMicros,
            long rechargedDeltaMicros,
            long bonusGrantedDeltaMicros,
            long consumedCashDeltaMicros,
            long consumedBonusDeltaMicros,
            UUID lastAppliedEventId,
            OffsetDateTime updatedAt
    );

    /**
     * Moves the bonus snapshot when a batch expires.
     *
     * <p>Leaves the fold position untouched: this is not an event from the delivery channel, so
     * moving {@code last_applied_event_id} here would make the position skip backwards or forwards
     * for reasons unrelated to what the consumer has folded.
     */
    boolean applyExpiryDelta(UUID orgId, long bonusDeltaMicros, OffsetDateTime updatedAt);

    boolean updateLowBalanceThreshold(UUID orgId, Long thresholdMicros, long expectedVersion, OffsetDateTime updatedAt);
}

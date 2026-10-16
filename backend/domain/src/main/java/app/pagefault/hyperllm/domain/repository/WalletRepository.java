package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.billing.Wallet;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository {

    void insert(Wallet wallet);

    Optional<Wallet> findByOrgId(UUID orgId);

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

    boolean updateLowBalanceThreshold(UUID orgId, Long thresholdMicros, long expectedVersion, OffsetDateTime updatedAt);
}

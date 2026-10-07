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

    boolean updateLowBalanceThreshold(UUID orgId, Long thresholdMicros, long expectedVersion, OffsetDateTime updatedAt);
}

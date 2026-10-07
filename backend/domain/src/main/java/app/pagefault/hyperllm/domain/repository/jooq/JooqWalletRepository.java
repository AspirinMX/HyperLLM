package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.jooq.tables.records.WalletsRecord;
import app.pagefault.hyperllm.domain.model.billing.Wallet;
import app.pagefault.hyperllm.domain.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.WALLETS;

@Repository
@RequiredArgsConstructor
public class JooqWalletRepository implements WalletRepository {

    private final DSLContext dsl;

    @Override
    public void insert(Wallet wallet) {
        dsl.insertInto(WALLETS)
                .set(WALLETS.ORG_ID, wallet.orgId())
                .set(WALLETS.CASH_BALANCE_MICROS, wallet.cashBalanceMicros())
                .set(WALLETS.BONUS_BALANCE_MICROS, wallet.bonusBalanceMicros())
                .set(WALLETS.TOTAL_RECHARGED_MICROS, wallet.totalRechargedMicros())
                .set(WALLETS.TOTAL_BONUS_GRANTED_MICROS, wallet.totalBonusGrantedMicros())
                .set(WALLETS.TOTAL_CONSUMED_CASH_MICROS, wallet.totalConsumedCashMicros())
                .set(WALLETS.TOTAL_CONSUMED_BONUS_MICROS, wallet.totalConsumedBonusMicros())
                .set(WALLETS.LOW_BALANCE_THRESHOLD_MICROS, wallet.lowBalanceThresholdMicros())
                .set(WALLETS.VERSION, wallet.version())
                .set(WALLETS.UPDATED_AT, wallet.updatedAt())
                .execute();
    }

    @Override
    public Optional<Wallet> findByOrgId(UUID orgId) {
        return dsl.selectFrom(WALLETS)
                .where(WALLETS.ORG_ID.eq(orgId))
                .fetchOptional(this::toDomain);
    }

    @Override
    public boolean applyDelta(
            UUID orgId,
            long cashDeltaMicros,
            long bonusDeltaMicros,
            long rechargedDeltaMicros,
            long bonusGrantedDeltaMicros,
            long consumedCashDeltaMicros,
            long consumedBonusDeltaMicros,
            OffsetDateTime updatedAt
    ) {
        int updated = dsl.update(WALLETS)
                .set(WALLETS.CASH_BALANCE_MICROS, WALLETS.CASH_BALANCE_MICROS.plus(cashDeltaMicros))
                .set(WALLETS.BONUS_BALANCE_MICROS, WALLETS.BONUS_BALANCE_MICROS.plus(bonusDeltaMicros))
                .set(WALLETS.TOTAL_RECHARGED_MICROS, WALLETS.TOTAL_RECHARGED_MICROS.plus(rechargedDeltaMicros))
                .set(WALLETS.TOTAL_BONUS_GRANTED_MICROS, WALLETS.TOTAL_BONUS_GRANTED_MICROS.plus(bonusGrantedDeltaMicros))
                .set(WALLETS.TOTAL_CONSUMED_CASH_MICROS, WALLETS.TOTAL_CONSUMED_CASH_MICROS.plus(consumedCashDeltaMicros))
                .set(WALLETS.TOTAL_CONSUMED_BONUS_MICROS, WALLETS.TOTAL_CONSUMED_BONUS_MICROS.plus(consumedBonusDeltaMicros))
                .set(WALLETS.VERSION, WALLETS.VERSION.plus(1))
                .set(WALLETS.UPDATED_AT, updatedAt)
                .where(WALLETS.ORG_ID.eq(orgId))
                .and(WALLETS.CASH_BALANCE_MICROS.plus(cashDeltaMicros).ge(0L))
                .and(WALLETS.BONUS_BALANCE_MICROS.plus(bonusDeltaMicros).ge(0L))
                .execute();
        return updated == 1;
    }

    @Override
    public boolean updateLowBalanceThreshold(UUID orgId, Long thresholdMicros, long expectedVersion, OffsetDateTime updatedAt) {
        int updated = dsl.update(WALLETS)
                .set(WALLETS.LOW_BALANCE_THRESHOLD_MICROS, thresholdMicros)
                .set(WALLETS.VERSION, WALLETS.VERSION.plus(1))
                .set(WALLETS.UPDATED_AT, updatedAt)
                .where(WALLETS.ORG_ID.eq(orgId))
                .and(WALLETS.VERSION.eq(expectedVersion))
                .execute();
        return updated == 1;
    }

    private Wallet toDomain(WalletsRecord r) {
        return new Wallet(
                r.getOrgId(),
                r.getCashBalanceMicros(),
                r.getBonusBalanceMicros(),
                r.getTotalRechargedMicros(),
                r.getTotalBonusGrantedMicros(),
                r.getTotalConsumedCashMicros(),
                r.getTotalConsumedBonusMicros(),
                r.getLowBalanceThresholdMicros(),
                r.getVersion(),
                r.getUpdatedAt()
        );
    }
}

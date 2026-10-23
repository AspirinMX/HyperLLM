package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.jooq.tables.records.WalletsRecord;
import app.pagefault.hyperllm.domain.model.billing.Wallet;
import app.pagefault.hyperllm.domain.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
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
    public java.util.Map<UUID, Balance> findBalancesByOrgIds(java.util.Collection<UUID> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) {
            return java.util.Map.of();
        }
        return dsl.select(WALLETS.ORG_ID, WALLETS.CASH_BALANCE_MICROS, WALLETS.BONUS_BALANCE_MICROS)
                .from(WALLETS)
                .where(WALLETS.ORG_ID.in(orgIds))
                .fetchMap(
                        record -> record.get(WALLETS.ORG_ID),
                        record -> new Balance(
                                record.get(WALLETS.CASH_BALANCE_MICROS),
                                record.get(WALLETS.BONUS_BALANCE_MICROS)));
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
    public boolean applySettlementDelta(
            UUID orgId,
            long cashDeltaMicros,
            long bonusDeltaMicros,
            long consumedCashDeltaMicros,
            long consumedBonusDeltaMicros,
            OffsetDateTime updatedAt
    ) {
        int updated = dsl.update(WALLETS)
                .set(WALLETS.CASH_BALANCE_MICROS, DSL.greatest(WALLETS.CASH_BALANCE_MICROS.plus(cashDeltaMicros), DSL.inline(0L)))
                .set(WALLETS.BONUS_BALANCE_MICROS, DSL.greatest(WALLETS.BONUS_BALANCE_MICROS.plus(bonusDeltaMicros), DSL.inline(0L)))
                .set(WALLETS.TOTAL_CONSUMED_CASH_MICROS, WALLETS.TOTAL_CONSUMED_CASH_MICROS.plus(consumedCashDeltaMicros))
                .set(WALLETS.TOTAL_CONSUMED_BONUS_MICROS, WALLETS.TOTAL_CONSUMED_BONUS_MICROS.plus(consumedBonusDeltaMicros))
                .set(WALLETS.VERSION, WALLETS.VERSION.plus(1))
                .set(WALLETS.UPDATED_AT, updatedAt)
                .where(WALLETS.ORG_ID.eq(orgId))
                .execute();
        return updated == 1;
    }

    @Override
    public boolean applyEventDelta(
            UUID orgId,
            long cashDeltaMicros,
            long bonusDeltaMicros,
            long rechargedDeltaMicros,
            long bonusGrantedDeltaMicros,
            long consumedCashDeltaMicros,
            long consumedBonusDeltaMicros,
            UUID lastAppliedEventId,
            OffsetDateTime updatedAt
    ) {
        int updated = dsl.update(WALLETS)
                .set(WALLETS.CASH_BALANCE_MICROS, WALLETS.CASH_BALANCE_MICROS.plus(cashDeltaMicros))
                .set(WALLETS.BONUS_BALANCE_MICROS, WALLETS.BONUS_BALANCE_MICROS.plus(bonusDeltaMicros))
                .set(WALLETS.TOTAL_RECHARGED_MICROS, WALLETS.TOTAL_RECHARGED_MICROS.plus(rechargedDeltaMicros))
                .set(WALLETS.TOTAL_BONUS_GRANTED_MICROS, WALLETS.TOTAL_BONUS_GRANTED_MICROS.plus(bonusGrantedDeltaMicros))
                .set(WALLETS.TOTAL_CONSUMED_CASH_MICROS, WALLETS.TOTAL_CONSUMED_CASH_MICROS.plus(consumedCashDeltaMicros))
                .set(WALLETS.TOTAL_CONSUMED_BONUS_MICROS, WALLETS.TOTAL_CONSUMED_BONUS_MICROS.plus(consumedBonusDeltaMicros))
                .set(WALLETS.LAST_APPLIED_EVENT_ID, lastAppliedEventId)
                .set(WALLETS.VERSION, WALLETS.VERSION.plus(1))
                .set(WALLETS.UPDATED_AT, updatedAt)
                .where(WALLETS.ORG_ID.eq(orgId))
                .execute();
        return updated == 1;
    }

    @Override
    public boolean applyExpiryDelta(UUID orgId, long bonusDeltaMicros, OffsetDateTime updatedAt) {
        int updated = dsl.update(WALLETS)
                .set(WALLETS.BONUS_BALANCE_MICROS, WALLETS.BONUS_BALANCE_MICROS.plus(bonusDeltaMicros))
                .set(WALLETS.VERSION, WALLETS.VERSION.plus(1))
                .set(WALLETS.UPDATED_AT, updatedAt)
                .where(WALLETS.ORG_ID.eq(orgId))
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

package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.jooq.tables.records.WalletLedgerRecord;
import app.pagefault.hyperllm.domain.model.billing.WalletLedgerEntry;
import app.pagefault.hyperllm.domain.model.enums.LedgerType;
import app.pagefault.hyperllm.domain.repository.WalletLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.WALLET_LEDGER;

@Repository
@RequiredArgsConstructor
public class JooqWalletLedgerRepository implements WalletLedgerRepository {

    private final DSLContext dsl;

    @Override
    public boolean insertIdempotent(WalletLedgerEntry entry) {
        int inserted = dsl.insertInto(WALLET_LEDGER)
                .set(WALLET_LEDGER.ID, entry.id())
                .set(WALLET_LEDGER.ORG_ID, entry.orgId())
                .set(WALLET_LEDGER.PROJECT_ID, entry.projectId())
                .set(WALLET_LEDGER.API_KEY_ID, entry.apiKeyId())
                .set(WALLET_LEDGER.LEDGER_TYPE, entry.ledgerType().name())
                .set(WALLET_LEDGER.IDEMPOTENCY_KEY, entry.idempotencyKey())
                .set(WALLET_LEDGER.CASH_DELTA_MICROS, entry.cashDeltaMicros())
                .set(WALLET_LEDGER.BONUS_DELTA_MICROS, entry.bonusDeltaMicros())
                .set(WALLET_LEDGER.PACK_DELTA_UNITS, entry.packDeltaUnits())
                .set(WALLET_LEDGER.CASH_BALANCE_AFTER_MICROS, entry.cashBalanceAfterMicros())
                .set(WALLET_LEDGER.BONUS_BALANCE_AFTER_MICROS, entry.bonusBalanceAfterMicros())
                .set(WALLET_LEDGER.ALLOCATION_DETAIL_JSON, PgJooqSupport.toJsonb(entry.allocationDetailJson()))
                .set(WALLET_LEDGER.REF_REQUEST_ID, entry.refRequestId())
                .set(WALLET_LEDGER.REF_ORDER_ID, entry.refOrderId())
                .set(WALLET_LEDGER.REF_ADJUSTMENT_ID, entry.refAdjustmentId())
                .set(WALLET_LEDGER.DESCRIPTION, entry.description())
                .set(WALLET_LEDGER.HOLD_ID, entry.holdId())
                .set(WALLET_LEDGER.SHORTFALL_MICROS, entry.shortfallMicros())
                .set(WALLET_LEDGER.SETTLED_AFTER_RECLAIM, entry.settledAfterReclaim())
                .set(WALLET_LEDGER.CREATED_AT, entry.createdAt())
                .onConflictDoNothing()
                .execute();
        return inserted == 1;
    }

    @Override
    public Optional<UUID> findFirstByRequestKey(UUID orgId, String idempotencyKey) {
        return dsl.select(WALLET_LEDGER.ID).from(WALLET_LEDGER)
                .where(WALLET_LEDGER.ORG_ID.eq(orgId).and(WALLET_LEDGER.IDEMPOTENCY_KEY.eq(idempotencyKey)))
                .orderBy(WALLET_LEDGER.CREATED_AT.asc()).limit(1).fetchOptional(WALLET_LEDGER.ID);
    }

    @Override
    public PageResponse<WalletLedgerEntry> listByOrg(UUID orgId, PageQuery pageQuery) {
        Condition cond = WALLET_LEDGER.ORG_ID.eq(orgId);
        return PgJooqSupport.fetchPage(
                dsl,
                WALLET_LEDGER,
                cond,
                WALLET_LEDGER.CREATED_AT.desc(),
                pageQuery,
                this::toDomain
        );
    }

    private WalletLedgerEntry toDomain(WalletLedgerRecord r) {
        return new WalletLedgerEntry(
                r.getId(),
                r.getOrgId(),
                r.getProjectId(),
                r.getApiKeyId(),
                PgJooqSupport.toEnum(LedgerType.class, r.getLedgerType()),
                r.getIdempotencyKey(),
                r.getCashDeltaMicros(),
                r.getBonusDeltaMicros(),
                r.getPackDeltaUnits(),
                r.getCashBalanceAfterMicros(),
                r.getBonusBalanceAfterMicros(),
                PgJooqSupport.fromJsonb(r.getAllocationDetailJson()),
                r.getRefRequestId(),
                r.getRefOrderId(),
                r.getRefAdjustmentId(),
                r.getDescription(),
                r.getCreatedAt(),
                r.getHoldId(),
                r.getShortfallMicros() == null ? 0L : r.getShortfallMicros(),
                Boolean.TRUE.equals(r.getSettledAfterReclaim())
        );
    }
}
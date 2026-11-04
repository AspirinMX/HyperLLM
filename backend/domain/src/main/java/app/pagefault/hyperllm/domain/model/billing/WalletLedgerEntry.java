package app.pagefault.hyperllm.domain.model.billing;

import app.pagefault.hyperllm.domain.model.enums.LedgerType;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record WalletLedgerEntry(
        UUID id,
        UUID orgId,
        UUID projectId,
        UUID apiKeyId,
        LedgerType ledgerType,
        String idempotencyKey,
        long cashDeltaMicros,
        long bonusDeltaMicros,
        long packDeltaUnits,
        Long cashBalanceAfterMicros,
        Long bonusBalanceAfterMicros,
        String allocationDetailJson,
        String refRequestId,
        UUID refOrderId,
        UUID refAdjustmentId,
        String description,
        OffsetDateTime createdAt,
        /** Pre-hold that produced this row; {@code null} for rows written before Phase 2.3. */
        UUID holdId,
        /** Part of the charge the balance could not cover; kept out of the deltas. */
        long shortfallMicros,
        /** True when the lease had already been reclaimed and the charge was applied anyway. */
        boolean settledAfterReclaim,
        /** UTC month this charge belongs to on the project budget, {@code null} for other rows. */
        LocalDate budgetMonth,
        /** Rated value of the request on that budget; independent of which bucket paid for it. */
        long budgetChargeMicros
) {

    /** Entry written before project budgets existed. */
    public WalletLedgerEntry(
            UUID id, UUID orgId, UUID projectId, UUID apiKeyId, LedgerType ledgerType,
            String idempotencyKey, long cashDeltaMicros, long bonusDeltaMicros, long packDeltaUnits,
            Long cashBalanceAfterMicros, Long bonusBalanceAfterMicros, String allocationDetailJson,
            String refRequestId, UUID refOrderId, UUID refAdjustmentId, String description,
            OffsetDateTime createdAt, UUID holdId, long shortfallMicros, boolean settledAfterReclaim) {
        this(id, orgId, projectId, apiKeyId, ledgerType, idempotencyKey, cashDeltaMicros,
                bonusDeltaMicros, packDeltaUnits, cashBalanceAfterMicros, bonusBalanceAfterMicros,
                allocationDetailJson, refRequestId, refOrderId, refAdjustmentId, description,
                createdAt, holdId, shortfallMicros, settledAfterReclaim, null, 0L);
    }

    /** Entry without the settlement extras, for adjustments and grants written before they existed. */
    public WalletLedgerEntry(
            UUID id, UUID orgId, UUID projectId, UUID apiKeyId, LedgerType ledgerType,
            String idempotencyKey, long cashDeltaMicros, long bonusDeltaMicros, long packDeltaUnits,
            Long cashBalanceAfterMicros, Long bonusBalanceAfterMicros, String allocationDetailJson,
            String refRequestId, UUID refOrderId, UUID refAdjustmentId, String description,
            OffsetDateTime createdAt, UUID holdId) {
        this(id, orgId, projectId, apiKeyId, ledgerType, idempotencyKey, cashDeltaMicros,
                bonusDeltaMicros, packDeltaUnits, cashBalanceAfterMicros, bonusBalanceAfterMicros,
                allocationDetailJson, refRequestId, refOrderId, refAdjustmentId, description,
                createdAt, holdId, 0L, false, null, 0L);
    }
}
package app.pagefault.hyperllm.domain.model.billing;

import app.pagefault.hyperllm.domain.model.enums.LedgerType;

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
        UUID holdId
) {
}
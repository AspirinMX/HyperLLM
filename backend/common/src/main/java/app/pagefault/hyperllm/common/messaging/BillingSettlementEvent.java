package app.pagefault.hyperllm.common.messaging;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Asynchronous billing settlement event emitted by gateway and persisted idempotently by admin
 * into partitioned {@code wallet_ledger}.
 *
 * <p>{@code packDeltaUnits} is negative on consumption and {@code allocationDetailJson} carries the
 * versioned allocation detail. Both were added in Phase 2.2; older producers simply omit them,
 * which Jackson maps to {@code 0} and {@code null}.
 */
public record BillingSettlementEvent(
        String requestId,
        OffsetDateTime eventCreatedAt,
        UUID orgId,
        UUID projectId,
        UUID apiKeyId,
        long cashDeltaMicros,
        long bonusDeltaMicros,
        long packDeltaUnits,
        String idempotencyKey,
        String description,
        String allocationDetailJson
) {
}

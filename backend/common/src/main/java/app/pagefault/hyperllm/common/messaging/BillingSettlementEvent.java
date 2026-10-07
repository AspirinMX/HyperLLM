package app.pagefault.hyperllm.common.messaging;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Asynchronous billing settlement event emitted by gateway and persisted idempotently by admin
 * into partitioned {@code wallet_ledger}.
 */
public record BillingSettlementEvent(
        String requestId,
        OffsetDateTime eventCreatedAt,
        UUID orgId,
        UUID projectId,
        UUID apiKeyId,
        long cashDeltaMicros,
        long bonusDeltaMicros,
        String idempotencyKey,
        String description
) {
}

package app.pagefault.hyperllm.domain.model.billing;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Wallet(
        UUID orgId,
        long cashBalanceMicros,
        long bonusBalanceMicros,
        long totalRechargedMicros,
        long totalBonusGrantedMicros,
        long totalConsumedCashMicros,
        long totalConsumedBonusMicros,
        Long lowBalanceThresholdMicros,
        long version,
        OffsetDateTime updatedAt
) {
}

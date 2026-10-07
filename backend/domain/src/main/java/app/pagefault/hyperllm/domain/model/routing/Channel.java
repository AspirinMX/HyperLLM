package app.pagefault.hyperllm.domain.model.routing;

import app.pagefault.hyperllm.domain.model.enums.ChannelStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Channel(
        UUID id,
        UUID providerId,
        String name,
        String region,
        String baseUrlOverride,
        byte[] encryptedCredentials,
        short credentialKeyVersion,
        int defaultCostDiscountBps,
        Integer rpmLimit,
        Integer tpmLimit,
        Integer maxConcurrency,
        String healthCheckModel,
        ChannelStatus status,
        OffsetDateTime deletedAt,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

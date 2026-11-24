package app.pagefault.hyperllm.domain.model.routing;

import app.pagefault.hyperllm.domain.model.enums.BindingStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ModelChannelBinding(
        UUID id,
        UUID virtualModelId,
        UUID channelId,
        String upstreamModelName,
        String upstreamProtocol,
        short priorityTier,
        int weight,
        Integer costDiscountBpsOverride,
        String upstreamPricingOverrideJson,
        String extraHeadersJson,
        BindingStatus status,
        OffsetDateTime deletedAt,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public ModelChannelBinding(
            UUID id,
            UUID virtualModelId,
            UUID channelId,
            String upstreamModelName,
            short priorityTier,
            int weight,
            Integer costDiscountBpsOverride,
            String upstreamPricingOverrideJson,
            String extraHeadersJson,
            BindingStatus status,
            OffsetDateTime deletedAt,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        this(id, virtualModelId, channelId, upstreamModelName, null, priorityTier, weight,
                costDiscountBpsOverride, upstreamPricingOverrideJson, extraHeadersJson,
                status, deletedAt, version, createdAt, updatedAt);
    }
}

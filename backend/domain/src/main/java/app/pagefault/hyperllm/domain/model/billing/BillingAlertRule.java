package app.pagefault.hyperllm.domain.model.billing;

import app.pagefault.hyperllm.domain.model.enums.AlertType;
import app.pagefault.hyperllm.domain.model.enums.ToggleStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record BillingAlertRule(
        UUID id,
        UUID orgId,
        UUID projectId,
        AlertType alertType,
        long thresholdMicros,
        List<UUID> endpointIds,
        int cooldownMinutes,
        OffsetDateTime lastTriggeredAt,
        ToggleStatus status,
        OffsetDateTime deletedAt,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

package app.pagefault.hyperllm.console.dto;

import app.pagefault.hyperllm.domain.model.enums.AlertType;
import app.pagefault.hyperllm.domain.model.enums.NotificationChannelType;
import app.pagefault.hyperllm.domain.model.enums.ToggleStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class AlertDtos {

    private AlertDtos() {
    }

    public record CreateEndpointRequest(
            @NotNull NotificationChannelType channelType,
            @NotBlank @Size(max = 512) String target
    ) {
    }

    public record UpdateEndpointRequest(
            @NotNull ToggleStatus status,
            @NotNull Long version
    ) {
    }

    public record EndpointDto(
            UUID id,
            UUID orgId,
            NotificationChannelType channelType,
            String target,
            ToggleStatus status,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    /** The signing secret is shown exactly once, when the endpoint is created. */
    public record CreatedEndpointDto(EndpointDto endpoint, String signingSecret) {
    }

    public record CreateAlertRuleRequest(
            @NotNull AlertType alertType,
            UUID projectId,
            @Min(0) long thresholdMicros,
            @NotEmpty List<UUID> endpointIds,
            @Min(1) @Max(1440) Integer cooldownMinutes
    ) {
    }

    public record AlertRuleDto(
            UUID id,
            UUID orgId,
            UUID projectId,
            AlertType alertType,
            long thresholdMicros,
            List<UUID> endpointIds,
            int cooldownMinutes,
            OffsetDateTime lastTriggeredAt,
            ToggleStatus status,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }
}

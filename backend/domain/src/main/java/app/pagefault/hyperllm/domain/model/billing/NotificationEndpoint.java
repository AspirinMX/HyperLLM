package app.pagefault.hyperllm.domain.model.billing;

import app.pagefault.hyperllm.domain.model.enums.NotificationChannelType;
import app.pagefault.hyperllm.domain.model.enums.ToggleStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationEndpoint(
        UUID id,
        UUID orgId,
        NotificationChannelType channelType,
        String target,
        byte[] encryptedSigningSecret,
        ToggleStatus status,
        OffsetDateTime deletedAt,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

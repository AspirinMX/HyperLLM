package app.pagefault.hyperllm.domain.model.billing;

import app.pagefault.hyperllm.domain.model.enums.ActorType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditLogEntry(
        UUID id,
        ActorType actorType,
        UUID actorId,
        UUID orgId,
        String action,
        String resourceType,
        String resourceId,
        String diffJson,
        String clientIp,
        String traceId,
        OffsetDateTime createdAt
) {
}

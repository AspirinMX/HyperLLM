package app.pagefault.hyperllm.common.cache;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Ephemeral Playground token metadata stored in Redis under {@code gw:sandbox:{sha256_hash}}.
 */
public record SandboxTokenPayload(
        UUID orgId,
        UUID projectId,
        UUID userId,
        List<String> allowedModels,
        OffsetDateTime expiresAt
) {
}

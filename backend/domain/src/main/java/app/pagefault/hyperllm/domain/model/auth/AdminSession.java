package app.pagefault.hyperllm.domain.model.auth;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminSession(
        UUID id,
        UUID adminUserId,
        String tokenHash,
        OffsetDateTime mfaVerifiedAt,
        String clientIp,
        String userAgent,
        OffsetDateTime expiresAt,
        OffsetDateTime revokedAt,
        OffsetDateTime createdAt
) {
}

package app.pagefault.hyperllm.domain.model.auth;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RefreshToken(
        UUID id,
        UUID userId,
        UUID tokenFamilyId,
        String tokenHash,
        String clientIp,
        String userAgent,
        OffsetDateTime expiresAt,
        OffsetDateTime rotatedAt,
        OffsetDateTime revokedAt,
        OffsetDateTime createdAt
) {
}

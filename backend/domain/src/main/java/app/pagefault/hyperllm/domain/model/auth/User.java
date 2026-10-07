package app.pagefault.hyperllm.domain.model.auth;

import app.pagefault.hyperllm.domain.model.enums.UserStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record User(
        UUID id,
        String email,
        OffsetDateTime emailVerifiedAt,
        String displayName,
        String avatarUrl,
        String locale,
        String preferredCurrency,
        UUID defaultOrgId,
        UserStatus status,
        OffsetDateTime lastLoginAt,
        OffsetDateTime deletedAt,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

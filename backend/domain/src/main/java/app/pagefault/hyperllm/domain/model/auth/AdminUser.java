package app.pagefault.hyperllm.domain.model.auth;

import app.pagefault.hyperllm.domain.model.enums.AdminRole;
import app.pagefault.hyperllm.domain.model.enums.AdminStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AdminUser(
        UUID id,
        String username,
        String email,
        String passwordHash,
        AdminRole role,
        byte[] totpSecretEncrypted,
        boolean mfaEnabled,
        String recoveryCodesHashJson,
        List<String> allowedCidrs,
        AdminStatus status,
        OffsetDateTime lastLoginAt,
        OffsetDateTime deletedAt,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

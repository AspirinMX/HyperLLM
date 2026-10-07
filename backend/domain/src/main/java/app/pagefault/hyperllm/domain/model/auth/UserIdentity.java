package app.pagefault.hyperllm.domain.model.auth;

import app.pagefault.hyperllm.domain.model.enums.IdentityProvider;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserIdentity(
        UUID id,
        UUID userId,
        IdentityProvider provider,
        String providerSubject,
        UUID ssoConnectionId,
        String passwordHash,
        String providerEmail,
        String metadataJson,
        OffsetDateTime lastUsedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

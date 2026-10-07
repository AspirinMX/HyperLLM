package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.auth.AdminSession;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface AdminSessionRepository {

    void insert(AdminSession session);

    Optional<AdminSession> findByTokenHash(String tokenHash);

    void markMfaVerified(UUID sessionId, OffsetDateTime verifiedAt);

    int revokeByTokenHash(String tokenHash, OffsetDateTime revokedAt);

    int revokeAllByAdminUserId(UUID adminUserId, OffsetDateTime revokedAt);
}

package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.auth.RefreshToken;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {

    void insert(RefreshToken refreshToken);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    boolean markRotated(UUID tokenId, OffsetDateTime rotatedAt);

    int revokeFamily(UUID tokenFamilyId, OffsetDateTime revokedAt);

    int revokeByTokenHash(String tokenHash, OffsetDateTime revokedAt);

    int revokeAllByUserId(UUID userId, OffsetDateTime revokedAt);
}

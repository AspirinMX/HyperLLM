package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.jooq.tables.records.RefreshTokensRecord;
import app.pagefault.hyperllm.domain.model.auth.RefreshToken;
import app.pagefault.hyperllm.domain.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.REFRESH_TOKENS;

@Repository
@RequiredArgsConstructor
public class JooqRefreshTokenRepository implements RefreshTokenRepository {

    private final DSLContext dsl;

    @Override
    public void insert(RefreshToken refreshToken) {
        dsl.insertInto(REFRESH_TOKENS)
                .set(REFRESH_TOKENS.ID, refreshToken.id())
                .set(REFRESH_TOKENS.USER_ID, refreshToken.userId())
                .set(REFRESH_TOKENS.TOKEN_FAMILY_ID, refreshToken.tokenFamilyId())
                .set(REFRESH_TOKENS.TOKEN_HASH, refreshToken.tokenHash())
                .set(PgJooqSupport.inetMap(REFRESH_TOKENS.CLIENT_IP, refreshToken.clientIp()))
                .set(REFRESH_TOKENS.USER_AGENT, refreshToken.userAgent())
                .set(REFRESH_TOKENS.EXPIRES_AT, refreshToken.expiresAt())
                .set(REFRESH_TOKENS.ROTATED_AT, refreshToken.rotatedAt())
                .set(REFRESH_TOKENS.REVOKED_AT, refreshToken.revokedAt())
                .set(REFRESH_TOKENS.CREATED_AT, refreshToken.createdAt())
                .execute();
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return dsl.selectFrom(REFRESH_TOKENS)
                .where(REFRESH_TOKENS.TOKEN_HASH.eq(tokenHash))
                .fetchOptional(this::toDomain);
    }

    @Override
    public boolean markRotated(UUID tokenId, OffsetDateTime rotatedAt) {
        int updated = dsl.update(REFRESH_TOKENS)
                .set(REFRESH_TOKENS.ROTATED_AT, rotatedAt)
                .where(REFRESH_TOKENS.ID.eq(tokenId))
                .and(REFRESH_TOKENS.ROTATED_AT.isNull())
                .and(REFRESH_TOKENS.REVOKED_AT.isNull())
                .execute();
        return updated == 1;
    }

    @Override
    public int revokeFamily(UUID tokenFamilyId, OffsetDateTime revokedAt) {
        return dsl.update(REFRESH_TOKENS)
                .set(REFRESH_TOKENS.REVOKED_AT, revokedAt)
                .where(REFRESH_TOKENS.TOKEN_FAMILY_ID.eq(tokenFamilyId))
                .and(REFRESH_TOKENS.REVOKED_AT.isNull())
                .execute();
    }

    @Override
    public int revokeByTokenHash(String tokenHash, OffsetDateTime revokedAt) {
        return dsl.update(REFRESH_TOKENS)
                .set(REFRESH_TOKENS.REVOKED_AT, revokedAt)
                .where(REFRESH_TOKENS.TOKEN_HASH.eq(tokenHash))
                .and(REFRESH_TOKENS.REVOKED_AT.isNull())
                .execute();
    }

    @Override
    public int revokeAllByUserId(UUID userId, OffsetDateTime revokedAt) {
        return dsl.update(REFRESH_TOKENS)
                .set(REFRESH_TOKENS.REVOKED_AT, revokedAt)
                .where(REFRESH_TOKENS.USER_ID.eq(userId))
                .and(REFRESH_TOKENS.REVOKED_AT.isNull())
                .execute();
    }

    private RefreshToken toDomain(RefreshTokensRecord r) {
        return new RefreshToken(
                r.getId(),
                r.getUserId(),
                r.getTokenFamilyId(),
                r.getTokenHash(),
                PgJooqSupport.fromInet(r.getClientIp()),
                r.getUserAgent(),
                r.getExpiresAt(),
                r.getRotatedAt(),
                r.getRevokedAt(),
                r.getCreatedAt()
        );
    }
}

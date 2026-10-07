package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.jooq.tables.records.AdminSessionsRecord;
import app.pagefault.hyperllm.domain.model.auth.AdminSession;
import app.pagefault.hyperllm.domain.repository.AdminSessionRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.ADMIN_SESSIONS;

@Repository
@RequiredArgsConstructor
public class JooqAdminSessionRepository implements AdminSessionRepository {

    private final DSLContext dsl;

    @Override
    public void insert(AdminSession session) {
        dsl.insertInto(ADMIN_SESSIONS)
                .set(ADMIN_SESSIONS.ID, session.id())
                .set(ADMIN_SESSIONS.ADMIN_USER_ID, session.adminUserId())
                .set(ADMIN_SESSIONS.TOKEN_HASH, session.tokenHash())
                .set(ADMIN_SESSIONS.MFA_VERIFIED_AT, session.mfaVerifiedAt())
                .set(PgJooqSupport.inetMap(ADMIN_SESSIONS.CLIENT_IP, session.clientIp()))
                .set(ADMIN_SESSIONS.USER_AGENT, session.userAgent())
                .set(ADMIN_SESSIONS.EXPIRES_AT, session.expiresAt())
                .set(ADMIN_SESSIONS.REVOKED_AT, session.revokedAt())
                .set(ADMIN_SESSIONS.CREATED_AT, session.createdAt())
                .execute();
    }

    @Override
    public Optional<AdminSession> findByTokenHash(String tokenHash) {
        return dsl.selectFrom(ADMIN_SESSIONS)
                .where(ADMIN_SESSIONS.TOKEN_HASH.eq(tokenHash))
                .fetchOptional(this::toDomain);
    }

    @Override
    public void markMfaVerified(UUID sessionId, OffsetDateTime verifiedAt) {
        dsl.update(ADMIN_SESSIONS)
                .set(ADMIN_SESSIONS.MFA_VERIFIED_AT, verifiedAt)
                .where(ADMIN_SESSIONS.ID.eq(sessionId))
                .execute();
    }

    @Override
    public int revokeByTokenHash(String tokenHash, OffsetDateTime revokedAt) {
        return dsl.update(ADMIN_SESSIONS)
                .set(ADMIN_SESSIONS.REVOKED_AT, revokedAt)
                .where(ADMIN_SESSIONS.TOKEN_HASH.eq(tokenHash))
                .and(ADMIN_SESSIONS.REVOKED_AT.isNull())
                .execute();
    }

    @Override
    public int revokeAllByAdminUserId(UUID adminUserId, OffsetDateTime revokedAt) {
        return dsl.update(ADMIN_SESSIONS)
                .set(ADMIN_SESSIONS.REVOKED_AT, revokedAt)
                .where(ADMIN_SESSIONS.ADMIN_USER_ID.eq(adminUserId))
                .and(ADMIN_SESSIONS.REVOKED_AT.isNull())
                .execute();
    }

    private AdminSession toDomain(AdminSessionsRecord r) {
        return new AdminSession(
                r.getId(),
                r.getAdminUserId(),
                r.getTokenHash(),
                r.getMfaVerifiedAt(),
                PgJooqSupport.fromInet(r.getClientIp()),
                r.getUserAgent(),
                r.getExpiresAt(),
                r.getRevokedAt(),
                r.getCreatedAt()
        );
    }
}

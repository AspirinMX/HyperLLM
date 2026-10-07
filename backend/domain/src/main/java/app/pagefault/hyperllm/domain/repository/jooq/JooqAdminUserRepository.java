package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.jooq.tables.records.AdminUsersRecord;
import app.pagefault.hyperllm.domain.model.auth.AdminUser;
import app.pagefault.hyperllm.domain.model.enums.AdminRole;
import app.pagefault.hyperllm.domain.model.enums.AdminStatus;
import app.pagefault.hyperllm.domain.repository.AdminUserRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.ADMIN_USERS;

@Repository
@RequiredArgsConstructor
public class JooqAdminUserRepository implements AdminUserRepository {

    private final DSLContext dsl;

    @Override
    public long countAll() {
        return dsl.fetchCount(dsl.selectFrom(ADMIN_USERS).where(ADMIN_USERS.DELETED_AT.isNull()));
    }

    @Override
    public void insert(AdminUser admin) {
        dsl.insertInto(ADMIN_USERS)
                .set(ADMIN_USERS.ID, admin.id())
                .set(ADMIN_USERS.USERNAME, admin.username())
                .set(ADMIN_USERS.EMAIL, admin.email())
                .set(ADMIN_USERS.PASSWORD_HASH, admin.passwordHash())
                .set(ADMIN_USERS.ROLE, admin.role().name())
                .set(ADMIN_USERS.TOTP_SECRET_ENCRYPTED, admin.totpSecretEncrypted())
                .set(ADMIN_USERS.MFA_ENABLED, admin.mfaEnabled())
                .set(ADMIN_USERS.RECOVERY_CODES_HASH_JSON, PgJooqSupport.toJsonb(admin.recoveryCodesHashJson()))
                .set(ADMIN_USERS.ALLOWED_CIDRS, PgJooqSupport.toCidrArray(admin.allowedCidrs(), ADMIN_USERS.ALLOWED_CIDRS))
                .set(ADMIN_USERS.STATUS, admin.status().name())
                .set(ADMIN_USERS.LAST_LOGIN_AT, admin.lastLoginAt())
                .set(ADMIN_USERS.DELETED_AT, admin.deletedAt())
                .set(ADMIN_USERS.VERSION, admin.version())
                .set(ADMIN_USERS.CREATED_AT, admin.createdAt())
                .set(ADMIN_USERS.UPDATED_AT, admin.updatedAt())
                .execute();
    }

    @Override
    public Optional<AdminUser> findById(UUID id) {
        return dsl.selectFrom(ADMIN_USERS)
                .where(ADMIN_USERS.ID.eq(id))
                .and(ADMIN_USERS.DELETED_AT.isNull())
                .fetchOptional(this::toDomain);
    }

    @Override
    public Optional<AdminUser> findActiveByLogin(String usernameOrEmail) {
        String normalized = usernameOrEmail.toLowerCase();
        return dsl.selectFrom(ADMIN_USERS)
                .where(ADMIN_USERS.DELETED_AT.isNull())
                .and(DSL.lower(ADMIN_USERS.USERNAME).eq(normalized)
                        .or(DSL.lower(ADMIN_USERS.EMAIL).eq(normalized)))
                .fetchOptional(this::toDomain);
    }

    @Override
    public boolean updateTotpSetup(UUID adminId, byte[] encryptedSecret, boolean mfaEnabled,
                                   String recoveryCodesHashJson, long expectedVersion, OffsetDateTime updatedAt) {
        int updated = dsl.update(ADMIN_USERS)
                .set(ADMIN_USERS.TOTP_SECRET_ENCRYPTED, encryptedSecret)
                .set(ADMIN_USERS.MFA_ENABLED, mfaEnabled)
                .set(ADMIN_USERS.RECOVERY_CODES_HASH_JSON, PgJooqSupport.toJsonb(recoveryCodesHashJson))
                .set(ADMIN_USERS.VERSION, ADMIN_USERS.VERSION.plus(1))
                .set(ADMIN_USERS.UPDATED_AT, updatedAt)
                .where(ADMIN_USERS.ID.eq(adminId))
                .and(ADMIN_USERS.VERSION.eq(expectedVersion))
                .and(ADMIN_USERS.DELETED_AT.isNull())
                .execute();
        return updated == 1;
    }

    @Override
    public void updateLastLogin(UUID adminId, OffsetDateTime loginAt) {
        dsl.update(ADMIN_USERS)
                .set(ADMIN_USERS.LAST_LOGIN_AT, loginAt)
                .set(ADMIN_USERS.UPDATED_AT, loginAt)
                .where(ADMIN_USERS.ID.eq(adminId))
                .execute();
    }

    private AdminUser toDomain(AdminUsersRecord r) {
        return new AdminUser(
                r.getId(),
                r.getUsername(),
                r.getEmail(),
                r.getPasswordHash(),
                PgJooqSupport.toEnum(AdminRole.class, r.getRole()),
                r.getTotpSecretEncrypted(),
                Boolean.TRUE.equals(r.getMfaEnabled()),
                PgJooqSupport.fromJsonb(r.getRecoveryCodesHashJson()),
                PgJooqSupport.fromCidrArray(r.getAllowedCidrs()),
                PgJooqSupport.toEnum(AdminStatus.class, r.getStatus()),
                r.getLastLoginAt(),
                r.getDeletedAt(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}

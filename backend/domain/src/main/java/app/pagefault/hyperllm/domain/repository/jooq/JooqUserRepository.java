package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.jooq.tables.records.UsersRecord;
import app.pagefault.hyperllm.domain.model.auth.User;
import app.pagefault.hyperllm.domain.model.enums.UserStatus;
import app.pagefault.hyperllm.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.USERS;

@Repository
@RequiredArgsConstructor
public class JooqUserRepository implements UserRepository {

    private final DSLContext dsl;

    @Override
    public void insert(User user) {
        dsl.insertInto(USERS)
                .set(USERS.ID, user.id())
                .set(USERS.EMAIL, user.email())
                .set(USERS.EMAIL_VERIFIED_AT, user.emailVerifiedAt())
                .set(USERS.DISPLAY_NAME, user.displayName())
                .set(USERS.AVATAR_URL, user.avatarUrl())
                .set(USERS.LOCALE, user.locale())
                .set(USERS.PREFERRED_CURRENCY, user.preferredCurrency())
                .set(USERS.DEFAULT_ORG_ID, user.defaultOrgId())
                .set(USERS.STATUS, user.status().name())
                .set(USERS.LAST_LOGIN_AT, user.lastLoginAt())
                .set(USERS.DELETED_AT, user.deletedAt())
                .set(USERS.VERSION, user.version())
                .set(USERS.CREATED_AT, user.createdAt())
                .set(USERS.UPDATED_AT, user.updatedAt())
                .execute();
    }

    @Override
    public Optional<User> findById(UUID id) {
        return dsl.selectFrom(USERS)
                .where(USERS.ID.eq(id))
                .and(USERS.DELETED_AT.isNull())
                .fetchOptional(this::toDomain);
    }

    @Override
    public Optional<User> findActiveByEmail(String email) {
        return dsl.selectFrom(USERS)
                .where(DSL.lower(USERS.EMAIL).eq(email.toLowerCase()))
                .and(USERS.DELETED_AT.isNull())
                .fetchOptional(this::toDomain);
    }

    @Override
    public boolean updateDefaultOrg(UUID userId, UUID orgId, long expectedVersion, OffsetDateTime updatedAt) {
        int updated = dsl.update(USERS)
                .set(USERS.DEFAULT_ORG_ID, orgId)
                .set(USERS.VERSION, USERS.VERSION.plus(1))
                .set(USERS.UPDATED_AT, updatedAt)
                .where(USERS.ID.eq(userId))
                .and(USERS.VERSION.eq(expectedVersion))
                .and(USERS.DELETED_AT.isNull())
                .execute();
        return updated == 1;
    }

    @Override
    public void updateLastLogin(UUID userId, OffsetDateTime loginAt) {
        dsl.update(USERS)
                .set(USERS.LAST_LOGIN_AT, loginAt)
                .set(USERS.UPDATED_AT, loginAt)
                .where(USERS.ID.eq(userId))
                .execute();
    }

    private User toDomain(UsersRecord r) {
        return new User(
                r.getId(),
                r.getEmail(),
                r.getEmailVerifiedAt(),
                r.getDisplayName(),
                r.getAvatarUrl(),
                r.getLocale(),
                r.getPreferredCurrency(),
                r.getDefaultOrgId(),
                PgJooqSupport.toEnum(UserStatus.class, r.getStatus()),
                r.getLastLoginAt(),
                r.getDeletedAt(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}

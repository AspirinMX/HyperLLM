package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.jooq.tables.records.UserIdentitiesRecord;
import app.pagefault.hyperllm.domain.model.auth.UserIdentity;
import app.pagefault.hyperllm.domain.model.enums.IdentityProvider;
import app.pagefault.hyperllm.domain.repository.UserIdentityRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.USER_IDENTITIES;

@Repository
@RequiredArgsConstructor
public class JooqUserIdentityRepository implements UserIdentityRepository {

    private final DSLContext dsl;

    @Override
    public void insert(UserIdentity identity) {
        dsl.insertInto(USER_IDENTITIES)
                .set(USER_IDENTITIES.ID, identity.id())
                .set(USER_IDENTITIES.USER_ID, identity.userId())
                .set(USER_IDENTITIES.PROVIDER, identity.provider().name())
                .set(USER_IDENTITIES.PROVIDER_SUBJECT, identity.providerSubject())
                .set(USER_IDENTITIES.SSO_CONNECTION_ID, identity.ssoConnectionId())
                .set(USER_IDENTITIES.PASSWORD_HASH, identity.passwordHash())
                .set(USER_IDENTITIES.PROVIDER_EMAIL, identity.providerEmail())
                .set(USER_IDENTITIES.METADATA_JSON, PgJooqSupport.toJsonb(identity.metadataJson()))
                .set(USER_IDENTITIES.LAST_USED_AT, identity.lastUsedAt())
                .set(USER_IDENTITIES.CREATED_AT, identity.createdAt())
                .set(USER_IDENTITIES.UPDATED_AT, identity.updatedAt())
                .execute();
    }

    @Override
    public Optional<UserIdentity> findGlobalByProviderAndSubject(IdentityProvider provider, String providerSubject) {
        return dsl.selectFrom(USER_IDENTITIES)
                .where(USER_IDENTITIES.PROVIDER.eq(provider.name()))
                .and(USER_IDENTITIES.PROVIDER_SUBJECT.eq(providerSubject))
                .and(USER_IDENTITIES.SSO_CONNECTION_ID.isNull())
                .fetchOptional(this::toDomain);
    }

    @Override
    public List<UserIdentity> findByUserId(UUID userId) {
        return dsl.selectFrom(USER_IDENTITIES)
                .where(USER_IDENTITIES.USER_ID.eq(userId))
                .fetch(this::toDomain);
    }

    @Override
    public void updateLastUsed(UUID identityId, OffsetDateTime lastUsedAt) {
        dsl.update(USER_IDENTITIES)
                .set(USER_IDENTITIES.LAST_USED_AT, lastUsedAt)
                .set(USER_IDENTITIES.UPDATED_AT, lastUsedAt)
                .where(USER_IDENTITIES.ID.eq(identityId))
                .execute();
    }

    private UserIdentity toDomain(UserIdentitiesRecord r) {
        return new UserIdentity(
                r.getId(),
                r.getUserId(),
                PgJooqSupport.toEnum(IdentityProvider.class, r.getProvider()),
                r.getProviderSubject(),
                r.getSsoConnectionId(),
                r.getPasswordHash(),
                r.getProviderEmail(),
                PgJooqSupport.fromJsonb(r.getMetadataJson()),
                r.getLastUsedAt(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}

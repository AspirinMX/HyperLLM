package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.jooq.tables.records.ApiKeysRecord;
import app.pagefault.hyperllm.domain.model.enums.ApiKeyStatus;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import app.pagefault.hyperllm.domain.model.enums.ProjectStatus;
import app.pagefault.hyperllm.domain.model.tenant.ApiKey;
import app.pagefault.hyperllm.domain.model.tenant.GatewayKeyContext;
import app.pagefault.hyperllm.domain.repository.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.API_KEYS;
import static app.pagefault.hyperllm.domain.jooq.Tables.CUSTOMER_TIERS;
import static app.pagefault.hyperllm.domain.jooq.Tables.ORGANIZATIONS;
import static app.pagefault.hyperllm.domain.jooq.Tables.PROJECTS;

@Repository
@RequiredArgsConstructor
public class JooqApiKeyRepository implements ApiKeyRepository {

    private final DSLContext dsl;

    @Override
    public void insert(ApiKey apiKey) {
        dsl.insertInto(API_KEYS)
                .set(API_KEYS.ID, apiKey.id())
                .set(API_KEYS.PROJECT_ID, apiKey.projectId())
                .set(API_KEYS.ORG_ID, apiKey.orgId())
                .set(API_KEYS.CREATED_BY_USER_ID, apiKey.createdByUserId())
                .set(API_KEYS.NAME, apiKey.name())
                .set(API_KEYS.KEY_PREFIX, apiKey.keyPrefix())
                .set(API_KEYS.KEY_HASH, apiKey.keyHash())
                .set(API_KEYS.PEPPER_VERSION, apiKey.pepperVersion())
                .set(API_KEYS.ALLOWED_MODELS, PgJooqSupport.toTextArray(apiKey.allowedModels()))
                .set(API_KEYS.IP_WHITELIST, PgJooqSupport.toCidrArray(apiKey.ipWhitelist(), API_KEYS.IP_WHITELIST))
                .set(API_KEYS.RPM_LIMIT, apiKey.rpmLimit())
                .set(API_KEYS.TPM_LIMIT, apiKey.tpmLimit())
                .set(API_KEYS.MAX_CONCURRENCY, apiKey.maxConcurrency())
                .set(API_KEYS.QUOTA_LIMIT_MICROS, apiKey.quotaLimitMicros())
                .set(API_KEYS.EXPIRES_AT, apiKey.expiresAt())
                .set(API_KEYS.LAST_USED_AT, apiKey.lastUsedAt())
                .set(API_KEYS.STATUS, apiKey.status().name())
                .set(API_KEYS.DELETED_AT, apiKey.deletedAt())
                .set(API_KEYS.VERSION, apiKey.version())
                .set(API_KEYS.CREATED_AT, apiKey.createdAt())
                .set(API_KEYS.UPDATED_AT, apiKey.updatedAt())
                .execute();
    }

    @Override
    public Optional<ApiKey> findById(UUID id) {
        return dsl.selectFrom(API_KEYS)
                .where(API_KEYS.ID.eq(id))
                .and(API_KEYS.DELETED_AT.isNull())
                .fetchOptional(this::toDomain);
    }

    @Override
    public Optional<GatewayKeyContext> findGatewayKeyContextByHash(String keyHash) {
        var effectiveDiscount = DSL.coalesce(ORGANIZATIONS.DISCOUNT_RATIO_BPS, CUSTOMER_TIERS.DEFAULT_DISCOUNT_BPS)
                .as("effective_discount_bps");

        return dsl.select(
                        API_KEYS.ID,
                        API_KEYS.PROJECT_ID,
                        API_KEYS.ORG_ID,
                        API_KEYS.KEY_PREFIX,
                        API_KEYS.KEY_HASH,
                        API_KEYS.PEPPER_VERSION,
                        API_KEYS.STATUS,
                        API_KEYS.EXPIRES_AT,
                        API_KEYS.ALLOWED_MODELS,
                        API_KEYS.IP_WHITELIST,
                        API_KEYS.RPM_LIMIT,
                        API_KEYS.TPM_LIMIT,
                        API_KEYS.MAX_CONCURRENCY,
                        API_KEYS.QUOTA_LIMIT_MICROS,
                        PROJECTS.STATUS,
                        PROJECTS.ALLOWED_MODELS,
                        PROJECTS.RPM_LIMIT,
                        PROJECTS.TPM_LIMIT,
                        PROJECTS.MAX_CONCURRENCY,
                        PROJECTS.MONTHLY_HARD_CAP_MICROS,
                        PROJECTS.MONTHLY_SOFT_CAP_MICROS,
                        PROJECTS.ZDR_DEBUG_ENABLED,
                        PROJECTS.ZDR_DEBUG_TTL_DAYS,
                        ORGANIZATIONS.STATUS,
                        ORGANIZATIONS.MAX_CONCURRENCY,
                        effectiveDiscount
                )
                .from(API_KEYS)
                .join(PROJECTS).on(PROJECTS.ID.eq(API_KEYS.PROJECT_ID))
                .join(ORGANIZATIONS).on(ORGANIZATIONS.ID.eq(API_KEYS.ORG_ID))
                .join(CUSTOMER_TIERS).on(CUSTOMER_TIERS.ID.eq(ORGANIZATIONS.TIER_ID))
                .where(API_KEYS.KEY_HASH.eq(keyHash))
                .and(API_KEYS.DELETED_AT.isNull())
                .and(PROJECTS.DELETED_AT.isNull())
                .and(ORGANIZATIONS.DELETED_AT.isNull())
                .fetchOptional(r -> toGatewayContext(r, effectiveDiscount.getName()));
    }

    @Override
    public PageResponse<ApiKey> listByProject(UUID projectId, PageQuery pageQuery) {
        Condition cond = API_KEYS.PROJECT_ID.eq(projectId).and(API_KEYS.DELETED_AT.isNull());
        return PgJooqSupport.fetchPage(dsl, API_KEYS, cond, API_KEYS.CREATED_AT.desc(), pageQuery, this::toDomain);
    }

    @Override
    public boolean updateConfig(
            UUID keyId,
            String name,
            List<String> allowedModels,
            List<String> ipWhitelist,
            Integer rpmLimit,
            Integer tpmLimit,
            Integer maxConcurrency,
            Long quotaLimitMicros,
            OffsetDateTime expiresAt,
            ApiKeyStatus status,
            long expectedVersion,
            OffsetDateTime updatedAt
    ) {
        int updated = dsl.update(API_KEYS)
                .set(API_KEYS.NAME, name)
                .set(API_KEYS.ALLOWED_MODELS, PgJooqSupport.toTextArray(allowedModels))
                .set(API_KEYS.IP_WHITELIST, PgJooqSupport.toCidrArray(ipWhitelist, API_KEYS.IP_WHITELIST))
                .set(API_KEYS.RPM_LIMIT, rpmLimit)
                .set(API_KEYS.TPM_LIMIT, tpmLimit)
                .set(API_KEYS.MAX_CONCURRENCY, maxConcurrency)
                .set(API_KEYS.QUOTA_LIMIT_MICROS, quotaLimitMicros)
                .set(API_KEYS.EXPIRES_AT, expiresAt)
                .set(API_KEYS.STATUS, status.name())
                .set(API_KEYS.VERSION, API_KEYS.VERSION.plus(1))
                .set(API_KEYS.UPDATED_AT, updatedAt)
                .where(API_KEYS.ID.eq(keyId))
                .and(API_KEYS.VERSION.eq(expectedVersion))
                .and(API_KEYS.DELETED_AT.isNull())
                .execute();
        return updated == 1;
    }

    @Override
    public boolean revoke(UUID keyId, long expectedVersion, OffsetDateTime deletedAt) {
        int updated = dsl.update(API_KEYS)
                .set(API_KEYS.STATUS, ApiKeyStatus.REVOKED.name())
                .set(API_KEYS.DELETED_AT, deletedAt)
                .set(API_KEYS.VERSION, API_KEYS.VERSION.plus(1))
                .set(API_KEYS.UPDATED_AT, deletedAt)
                .where(API_KEYS.ID.eq(keyId))
                .and(API_KEYS.VERSION.eq(expectedVersion))
                .and(API_KEYS.DELETED_AT.isNull())
                .execute();
        return updated == 1;
    }

    private ApiKey toDomain(ApiKeysRecord r) {
        return new ApiKey(
                r.getId(),
                r.getProjectId(),
                r.getOrgId(),
                r.getCreatedByUserId(),
                r.getName(),
                r.getKeyPrefix(),
                r.getKeyHash(),
                r.getPepperVersion(),
                PgJooqSupport.fromTextArray(r.getAllowedModels()),
                PgJooqSupport.fromCidrArray(r.getIpWhitelist()),
                r.getRpmLimit(),
                r.getTpmLimit(),
                r.getMaxConcurrency(),
                r.getQuotaLimitMicros(),
                r.getExpiresAt(),
                r.getLastUsedAt(),
                PgJooqSupport.toEnum(ApiKeyStatus.class, r.getStatus()),
                r.getDeletedAt(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }

    private GatewayKeyContext toGatewayContext(Record r, String discountAlias) {
        return new GatewayKeyContext(
                r.get(API_KEYS.ID),
                r.get(API_KEYS.PROJECT_ID),
                r.get(API_KEYS.ORG_ID),
                r.get(API_KEYS.KEY_PREFIX),
                r.get(API_KEYS.KEY_HASH),
                r.get(API_KEYS.PEPPER_VERSION),
                PgJooqSupport.toEnum(ApiKeyStatus.class, r.get(API_KEYS.STATUS)),
                r.get(API_KEYS.EXPIRES_AT),
                PgJooqSupport.fromTextArray(r.get(API_KEYS.ALLOWED_MODELS)),
                PgJooqSupport.fromCidrArray(r.get(API_KEYS.IP_WHITELIST)),
                r.get(API_KEYS.RPM_LIMIT),
                r.get(API_KEYS.TPM_LIMIT),
                r.get(API_KEYS.MAX_CONCURRENCY),
                r.get(API_KEYS.QUOTA_LIMIT_MICROS),
                PgJooqSupport.toEnum(ProjectStatus.class, r.get(PROJECTS.STATUS)),
                PgJooqSupport.fromTextArray(r.get(PROJECTS.ALLOWED_MODELS)),
                r.get(PROJECTS.RPM_LIMIT),
                r.get(PROJECTS.TPM_LIMIT),
                r.get(PROJECTS.MAX_CONCURRENCY),
                r.get(PROJECTS.MONTHLY_HARD_CAP_MICROS),
                r.get(PROJECTS.MONTHLY_SOFT_CAP_MICROS),
                Boolean.TRUE.equals(r.get(PROJECTS.ZDR_DEBUG_ENABLED)),
                r.get(PROJECTS.ZDR_DEBUG_TTL_DAYS),
                PgJooqSupport.toEnum(OrgStatus.class, r.get(ORGANIZATIONS.STATUS)),
                r.get(ORGANIZATIONS.MAX_CONCURRENCY),
                r.get(discountAlias, Integer.class)
        );
    }
}

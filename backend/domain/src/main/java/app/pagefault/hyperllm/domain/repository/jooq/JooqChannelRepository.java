package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.jooq.tables.records.ChannelsRecord;
import app.pagefault.hyperllm.domain.model.enums.ChannelStatus;
import app.pagefault.hyperllm.domain.model.routing.Channel;
import app.pagefault.hyperllm.domain.repository.ChannelRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.CHANNELS;

@Repository
@RequiredArgsConstructor
public class JooqChannelRepository implements ChannelRepository {

    private final DSLContext dsl;

    @Override
    public void insert(Channel channel) {
        dsl.insertInto(CHANNELS)
                .set(CHANNELS.ID, channel.id())
                .set(CHANNELS.PROVIDER_ID, channel.providerId())
                .set(CHANNELS.NAME, channel.name())
                .set(CHANNELS.REGION, channel.region())
                .set(CHANNELS.BASE_URL_OVERRIDE, channel.baseUrlOverride())
                .set(CHANNELS.ENCRYPTED_CREDENTIALS, channel.encryptedCredentials())
                .set(CHANNELS.CREDENTIAL_KEY_VERSION, channel.credentialKeyVersion())
                .set(CHANNELS.DEFAULT_COST_DISCOUNT_BPS, channel.defaultCostDiscountBps())
                .set(CHANNELS.RPM_LIMIT, channel.rpmLimit())
                .set(CHANNELS.TPM_LIMIT, channel.tpmLimit())
                .set(CHANNELS.MAX_CONCURRENCY, channel.maxConcurrency())
                .set(CHANNELS.HEALTH_CHECK_MODEL, channel.healthCheckModel())
                .set(CHANNELS.STATUS, channel.status().name())
                .set(CHANNELS.DELETED_AT, channel.deletedAt())
                .set(CHANNELS.VERSION, channel.version())
                .set(CHANNELS.CREATED_AT, channel.createdAt())
                .set(CHANNELS.UPDATED_AT, channel.updatedAt())
                .execute();
    }

    @Override
    public Optional<Channel> findById(UUID id) {
        return dsl.selectFrom(CHANNELS)
                .where(CHANNELS.ID.eq(id))
                .and(CHANNELS.DELETED_AT.isNull())
                .fetchOptional(this::toDomain);
    }

    @Override
    public PageResponse<Channel> listActive(PageQuery pageQuery) {
        Condition cond = CHANNELS.DELETED_AT.isNull();
        return PgJooqSupport.fetchPage(dsl, CHANNELS, cond, CHANNELS.CREATED_AT.desc(), pageQuery, this::toDomain);
    }

    @Override
    public boolean update(
            UUID channelId,
            String name,
            String region,
            String baseUrlOverride,
            byte[] encryptedCredentials,
            short credentialKeyVersion,
            int defaultCostDiscountBps,
            Integer rpmLimit,
            Integer tpmLimit,
            Integer maxConcurrency,
            String healthCheckModel,
            ChannelStatus status,
            long expectedVersion,
            OffsetDateTime updatedAt
    ) {
        int updated = dsl.update(CHANNELS)
                .set(CHANNELS.NAME, name)
                .set(CHANNELS.REGION, region)
                .set(CHANNELS.BASE_URL_OVERRIDE, baseUrlOverride)
                .set(CHANNELS.ENCRYPTED_CREDENTIALS, encryptedCredentials)
                .set(CHANNELS.CREDENTIAL_KEY_VERSION, credentialKeyVersion)
                .set(CHANNELS.DEFAULT_COST_DISCOUNT_BPS, defaultCostDiscountBps)
                .set(CHANNELS.RPM_LIMIT, rpmLimit)
                .set(CHANNELS.TPM_LIMIT, tpmLimit)
                .set(CHANNELS.MAX_CONCURRENCY, maxConcurrency)
                .set(CHANNELS.HEALTH_CHECK_MODEL, healthCheckModel)
                .set(CHANNELS.STATUS, status.name())
                .set(CHANNELS.VERSION, CHANNELS.VERSION.plus(1))
                .set(CHANNELS.UPDATED_AT, updatedAt)
                .where(CHANNELS.ID.eq(channelId))
                .and(CHANNELS.VERSION.eq(expectedVersion))
                .and(CHANNELS.DELETED_AT.isNull())
                .execute();
        return updated == 1;
    }

    @Override
    public boolean softDelete(UUID channelId, long expectedVersion, OffsetDateTime deletedAt) {
        int updated = dsl.update(CHANNELS)
                .set(CHANNELS.STATUS, ChannelStatus.DISABLED.name())
                .set(CHANNELS.DELETED_AT, deletedAt)
                .set(CHANNELS.VERSION, CHANNELS.VERSION.plus(1))
                .set(CHANNELS.UPDATED_AT, deletedAt)
                .where(CHANNELS.ID.eq(channelId))
                .and(CHANNELS.VERSION.eq(expectedVersion))
                .and(CHANNELS.DELETED_AT.isNull())
                .execute();
        return updated == 1;
    }

    private Channel toDomain(ChannelsRecord r) {
        return new Channel(
                r.getId(),
                r.getProviderId(),
                r.getName(),
                r.getRegion(),
                r.getBaseUrlOverride(),
                r.getEncryptedCredentials(),
                r.getCredentialKeyVersion(),
                r.getDefaultCostDiscountBps(),
                r.getRpmLimit(),
                r.getTpmLimit(),
                r.getMaxConcurrency(),
                r.getHealthCheckModel(),
                PgJooqSupport.toEnum(ChannelStatus.class, r.getStatus()),
                r.getDeletedAt(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}

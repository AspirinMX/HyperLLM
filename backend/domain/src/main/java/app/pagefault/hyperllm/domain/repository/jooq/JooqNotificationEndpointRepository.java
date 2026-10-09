package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.jooq.tables.records.NotificationEndpointsRecord;
import app.pagefault.hyperllm.domain.model.billing.NotificationEndpoint;
import app.pagefault.hyperllm.domain.model.enums.NotificationChannelType;
import app.pagefault.hyperllm.domain.model.enums.ToggleStatus;
import app.pagefault.hyperllm.domain.repository.NotificationEndpointRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.NOTIFICATION_ENDPOINTS;

@Repository
@RequiredArgsConstructor
public class JooqNotificationEndpointRepository implements NotificationEndpointRepository {

    private final DSLContext dsl;

    @Override
    public void insert(NotificationEndpoint endpoint) {
        dsl.insertInto(NOTIFICATION_ENDPOINTS)
                .set(NOTIFICATION_ENDPOINTS.ID, endpoint.id())
                .set(NOTIFICATION_ENDPOINTS.ORG_ID, endpoint.orgId())
                .set(NOTIFICATION_ENDPOINTS.CHANNEL_TYPE, endpoint.channelType().name())
                .set(NOTIFICATION_ENDPOINTS.TARGET, endpoint.target())
                .set(NOTIFICATION_ENDPOINTS.ENCRYPTED_SIGNING_SECRET, endpoint.encryptedSigningSecret())
                .set(NOTIFICATION_ENDPOINTS.STATUS, endpoint.status().name())
                .set(NOTIFICATION_ENDPOINTS.DELETED_AT, endpoint.deletedAt())
                .set(NOTIFICATION_ENDPOINTS.VERSION, endpoint.version())
                .set(NOTIFICATION_ENDPOINTS.CREATED_AT, endpoint.createdAt())
                .set(NOTIFICATION_ENDPOINTS.UPDATED_AT, endpoint.updatedAt())
                .execute();
    }

    @Override
    public Optional<NotificationEndpoint> findById(UUID id) {
        return dsl.selectFrom(NOTIFICATION_ENDPOINTS)
                .where(NOTIFICATION_ENDPOINTS.ID.eq(id))
                .fetchOptional(this::toDomain);
    }

    @Override
    public List<NotificationEndpoint> listActiveByOrg(UUID orgId) {
        return dsl.selectFrom(NOTIFICATION_ENDPOINTS)
                .where(NOTIFICATION_ENDPOINTS.ORG_ID.eq(orgId))
                .and(NOTIFICATION_ENDPOINTS.STATUS.eq(ToggleStatus.ACTIVE.name()))
                .and(NOTIFICATION_ENDPOINTS.DELETED_AT.isNull())
                .orderBy(NOTIFICATION_ENDPOINTS.CREATED_AT.asc())
                .fetch(this::toDomain);
    }

    @Override
    public boolean update(NotificationEndpoint next, long expectedVersion) {
        int updated = dsl.update(NOTIFICATION_ENDPOINTS)
                .set(NOTIFICATION_ENDPOINTS.CHANNEL_TYPE, next.channelType().name())
                .set(NOTIFICATION_ENDPOINTS.TARGET, next.target())
                .set(NOTIFICATION_ENDPOINTS.ENCRYPTED_SIGNING_SECRET, next.encryptedSigningSecret())
                .set(NOTIFICATION_ENDPOINTS.STATUS, next.status().name())
                .set(NOTIFICATION_ENDPOINTS.VERSION, NOTIFICATION_ENDPOINTS.VERSION.plus(1))
                .set(NOTIFICATION_ENDPOINTS.UPDATED_AT, next.updatedAt())
                .where(NOTIFICATION_ENDPOINTS.ID.eq(next.id()))
                .and(NOTIFICATION_ENDPOINTS.VERSION.eq(expectedVersion))
                .execute();
        return updated == 1;
    }

    @Override
    public boolean softDelete(UUID id, long expectedVersion, OffsetDateTime deletedAt) {
        int updated = dsl.update(NOTIFICATION_ENDPOINTS)
                .set(NOTIFICATION_ENDPOINTS.DELETED_AT, deletedAt)
                .set(NOTIFICATION_ENDPOINTS.VERSION, NOTIFICATION_ENDPOINTS.VERSION.plus(1))
                .set(NOTIFICATION_ENDPOINTS.UPDATED_AT, deletedAt)
                .where(NOTIFICATION_ENDPOINTS.ID.eq(id))
                .and(NOTIFICATION_ENDPOINTS.VERSION.eq(expectedVersion))
                .and(NOTIFICATION_ENDPOINTS.DELETED_AT.isNull())
                .execute();
        return updated == 1;
    }

    private NotificationEndpoint toDomain(NotificationEndpointsRecord r) {
        return new NotificationEndpoint(
                r.getId(),
                r.getOrgId(),
                PgJooqSupport.toEnum(NotificationChannelType.class, r.getChannelType()),
                r.getTarget(),
                r.getEncryptedSigningSecret(),
                PgJooqSupport.toEnum(ToggleStatus.class, r.getStatus()),
                r.getDeletedAt(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}

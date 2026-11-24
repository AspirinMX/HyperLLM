package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.jooq.tables.records.AuditLogsRecord;
import app.pagefault.hyperllm.domain.model.billing.AuditLogEntry;
import app.pagefault.hyperllm.domain.model.enums.ActorType;
import app.pagefault.hyperllm.domain.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.AUDIT_LOGS;

@Repository
@RequiredArgsConstructor
public class JooqAuditLogRepository implements AuditLogRepository {

    private final DSLContext dsl;

    @Override
    public void insert(AuditLogEntry entry) {
        dsl.insertInto(AUDIT_LOGS)
                .set(AUDIT_LOGS.ID, entry.id())
                .set(AUDIT_LOGS.ACTOR_TYPE, entry.actorType().name())
                .set(AUDIT_LOGS.ACTOR_ID, entry.actorId())
                .set(AUDIT_LOGS.ORG_ID, entry.orgId())
                .set(AUDIT_LOGS.ACTION, entry.action())
                .set(AUDIT_LOGS.RESOURCE_TYPE, entry.resourceType())
                .set(AUDIT_LOGS.RESOURCE_ID, entry.resourceId())
                .set(AUDIT_LOGS.DIFF_JSON, PgJooqSupport.toJsonb(entry.diffJson()))
                .set(PgJooqSupport.inetMap(AUDIT_LOGS.CLIENT_IP, entry.clientIp()))
                .set(AUDIT_LOGS.TRACE_ID, entry.traceId())
                .set(AUDIT_LOGS.CREATED_AT, entry.createdAt())
                .execute();
    }

    @Override
    public Optional<UUID> findFirstResourceEventId(UUID orgId, String action, String resourceId) {
        return dsl.select(AUDIT_LOGS.ID).from(AUDIT_LOGS)
                .where(AUDIT_LOGS.ORG_ID.eq(orgId).and(AUDIT_LOGS.ACTION.eq(action))
                        .and(AUDIT_LOGS.RESOURCE_ID.eq(resourceId)))
                .orderBy(AUDIT_LOGS.CREATED_AT.asc()).limit(1).fetchOptional(AUDIT_LOGS.ID);
    }

    @Override
    public PageResponse<AuditLogEntry> listByOrg(UUID orgId, PageQuery pageQuery) {
        Condition cond = AUDIT_LOGS.ORG_ID.eq(orgId);
        return PgJooqSupport.fetchPage(
                dsl,
                AUDIT_LOGS,
                cond,
                AUDIT_LOGS.CREATED_AT.desc(),
                pageQuery,
                this::toDomain
        );
    }

    @Override
    public PageResponse<AuditLogEntry> search(UUID orgId, String actionFilter, PageQuery pageQuery) {
        Condition cond = DSL.trueCondition();
        if (orgId != null) {
            cond = cond.and(AUDIT_LOGS.ORG_ID.eq(orgId));
        }
        if (actionFilter != null && !actionFilter.isBlank()) {
            cond = cond.and(AUDIT_LOGS.ACTION.containsIgnoreCase(actionFilter.trim()));
        }
        return PgJooqSupport.fetchPage(
                dsl,
                AUDIT_LOGS,
                cond,
                AUDIT_LOGS.CREATED_AT.desc(),
                pageQuery,
                this::toDomain
        );
    }

    private AuditLogEntry toDomain(AuditLogsRecord r) {
        return new AuditLogEntry(
                r.getId(),
                PgJooqSupport.toEnum(ActorType.class, r.getActorType()),
                r.getActorId(),
                r.getOrgId(),
                r.getAction(),
                r.getResourceType(),
                r.getResourceId(),
                PgJooqSupport.fromJsonb(r.getDiffJson()),
                PgJooqSupport.fromInet(r.getClientIp()),
                r.getTraceId(),
                r.getCreatedAt()
        );
    }
}

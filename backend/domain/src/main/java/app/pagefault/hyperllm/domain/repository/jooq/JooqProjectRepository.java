package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.jooq.tables.records.ProjectsRecord;
import app.pagefault.hyperllm.domain.model.enums.ProjectEnvironment;
import app.pagefault.hyperllm.domain.model.enums.ProjectStatus;
import app.pagefault.hyperllm.domain.model.tenant.Project;
import app.pagefault.hyperllm.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.PROJECTS;
import static app.pagefault.hyperllm.domain.jooq.Tables.PROJECT_MEMBERS;

@Repository
@RequiredArgsConstructor
public class JooqProjectRepository implements ProjectRepository {

    private final DSLContext dsl;

    @Override
    public void insert(Project project) {
        dsl.insertInto(PROJECTS)
                .set(PROJECTS.ID, project.id())
                .set(PROJECTS.ORG_ID, project.orgId())
                .set(PROJECTS.NAME, project.name())
                .set(PROJECTS.ENVIRONMENT, project.environment().name())
                .set(PROJECTS.MONTHLY_HARD_CAP_MICROS, project.monthlyHardCapMicros())
                .set(PROJECTS.MONTHLY_SOFT_CAP_MICROS, project.monthlySoftCapMicros())
                .set(PROJECTS.ALLOWED_MODELS, PgJooqSupport.toTextArray(project.allowedModels()))
                .set(PROJECTS.RPM_LIMIT, project.rpmLimit())
                .set(PROJECTS.TPM_LIMIT, project.tpmLimit())
                .set(PROJECTS.MAX_CONCURRENCY, project.maxConcurrency())
                .set(PROJECTS.ZDR_DEBUG_ENABLED, project.zdrDebugEnabled())
                .set(PROJECTS.ZDR_DEBUG_TTL_DAYS, project.zdrDebugTtlDays())
                .set(PROJECTS.GUARDRAIL_POLICY_ID, project.guardrailPolicyId())
                .set(PROJECTS.STATUS, project.status().name())
                .set(PROJECTS.DELETED_AT, project.deletedAt())
                .set(PROJECTS.VERSION, project.version())
                .set(PROJECTS.CREATED_AT, project.createdAt())
                .set(PROJECTS.UPDATED_AT, project.updatedAt())
                .execute();
    }

    @Override
    public Optional<Project> findById(UUID id) {
        return dsl.selectFrom(PROJECTS)
                .where(PROJECTS.ID.eq(id))
                .and(PROJECTS.DELETED_AT.isNull())
                .fetchOptional(this::toDomain);
    }

    @Override
    public PageResponse<Project> listByOrg(UUID orgId, PageQuery pageQuery) {
        Condition cond = PROJECTS.ORG_ID.eq(orgId).and(PROJECTS.DELETED_AT.isNull());
        return PgJooqSupport.fetchPage(dsl, PROJECTS, cond, PROJECTS.CREATED_AT.asc(), pageQuery, this::toDomain);
    }

    @Override
    public PageResponse<Project> listByOrgForMember(UUID orgId, UUID userId, PageQuery pageQuery) {
        Condition cond = PROJECTS.ORG_ID.eq(orgId)
                .and(PROJECTS.DELETED_AT.isNull())
                .and(PROJECT_MEMBERS.USER_ID.eq(userId));
        long total = dsl.fetchCount(
                dsl.select(PROJECTS.ID)
                        .from(PROJECTS)
                        .join(PROJECT_MEMBERS).on(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID))
                        .where(cond)
        );
        if (total == 0L) {
            return new PageResponse<>(List.of(), pageQuery.page(), pageQuery.pageSize(), 0L);
        }
        List<Project> items = dsl.select(PROJECTS.fields())
                .from(PROJECTS)
                .join(PROJECT_MEMBERS).on(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID))
                .where(cond)
                .orderBy(PROJECTS.CREATED_AT.asc())
                .limit(pageQuery.pageSize())
                .offset(pageQuery.offset())
                .fetchInto(ProjectsRecord.class)
                .stream()
                .map(this::toDomain)
                .toList();
        return new PageResponse<>(items, pageQuery.page(), pageQuery.pageSize(), total);
    }

    @Override
    public boolean updateSettings(
            UUID projectId,
            String name,
            Long monthlyHardCapMicros,
            Long monthlySoftCapMicros,
            List<String> allowedModels,
            Integer rpmLimit,
            Integer tpmLimit,
            Integer maxConcurrency,
            ProjectStatus status,
            long expectedVersion,
            OffsetDateTime updatedAt
    ) {
        int updated = dsl.update(PROJECTS)
                .set(PROJECTS.NAME, name)
                .set(PROJECTS.MONTHLY_HARD_CAP_MICROS, monthlyHardCapMicros)
                .set(PROJECTS.MONTHLY_SOFT_CAP_MICROS, monthlySoftCapMicros)
                .set(PROJECTS.ALLOWED_MODELS, PgJooqSupport.toTextArray(allowedModels))
                .set(PROJECTS.RPM_LIMIT, rpmLimit)
                .set(PROJECTS.TPM_LIMIT, tpmLimit)
                .set(PROJECTS.MAX_CONCURRENCY, maxConcurrency)
                .set(PROJECTS.STATUS, status.name())
                .set(PROJECTS.VERSION, PROJECTS.VERSION.plus(1))
                .set(PROJECTS.UPDATED_AT, updatedAt)
                .where(PROJECTS.ID.eq(projectId))
                .and(PROJECTS.VERSION.eq(expectedVersion))
                .and(PROJECTS.DELETED_AT.isNull())
                .execute();
        return updated == 1;
    }

    @Override
    public boolean softDelete(UUID projectId, long expectedVersion, OffsetDateTime deletedAt) {
        int updated = dsl.update(PROJECTS)
                .set(PROJECTS.STATUS, ProjectStatus.ARCHIVED.name())
                .set(PROJECTS.DELETED_AT, deletedAt)
                .set(PROJECTS.VERSION, PROJECTS.VERSION.plus(1))
                .set(PROJECTS.UPDATED_AT, deletedAt)
                .where(PROJECTS.ID.eq(projectId))
                .and(PROJECTS.VERSION.eq(expectedVersion))
                .and(PROJECTS.DELETED_AT.isNull())
                .execute();
        return updated == 1;
    }

    private Project toDomain(ProjectsRecord r) {
        return new Project(
                r.getId(),
                r.getOrgId(),
                r.getName(),
                PgJooqSupport.toEnum(ProjectEnvironment.class, r.getEnvironment()),
                r.getMonthlyHardCapMicros(),
                r.getMonthlySoftCapMicros(),
                PgJooqSupport.fromTextArray(r.getAllowedModels()),
                r.getRpmLimit(),
                r.getTpmLimit(),
                r.getMaxConcurrency(),
                Boolean.TRUE.equals(r.getZdrDebugEnabled()),
                r.getZdrDebugTtlDays(),
                r.getGuardrailPolicyId(),
                PgJooqSupport.toEnum(ProjectStatus.class, r.getStatus()),
                r.getDeletedAt(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}

package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.jooq.tables.records.OrganizationsRecord;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import app.pagefault.hyperllm.domain.model.tenant.Organization;
import app.pagefault.hyperllm.domain.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.ORGANIZATIONS;
import static app.pagefault.hyperllm.domain.jooq.Tables.ORG_MEMBERS;

@Repository
@RequiredArgsConstructor
public class JooqOrganizationRepository implements OrganizationRepository {

    private final DSLContext dsl;

    @Override
    public void insert(Organization org) {
        dsl.insertInto(ORGANIZATIONS)
                .set(ORGANIZATIONS.ID, org.id())
                .set(ORGANIZATIONS.NAME, org.name())
                .set(ORGANIZATIONS.SLUG, org.slug())
                .set(ORGANIZATIONS.OWNER_USER_ID, org.ownerUserId())
                .set(ORGANIZATIONS.TIER_ID, org.tierId())
                .set(ORGANIZATIONS.DISCOUNT_RATIO_BPS, org.discountRatioBps())
                .set(ORGANIZATIONS.MAX_CONCURRENCY, org.maxConcurrency())
                .set(ORGANIZATIONS.IS_PERSONAL, org.personal())
                .set(ORGANIZATIONS.STATUS, org.status().name())
                .set(ORGANIZATIONS.DELETED_AT, org.deletedAt())
                .set(ORGANIZATIONS.VERSION, org.version())
                .set(ORGANIZATIONS.CREATED_AT, org.createdAt())
                .set(ORGANIZATIONS.UPDATED_AT, org.updatedAt())
                .execute();
    }

    @Override
    public Optional<Organization> findById(UUID id) {
        return dsl.selectFrom(ORGANIZATIONS)
                .where(ORGANIZATIONS.ID.eq(id))
                .and(ORGANIZATIONS.DELETED_AT.isNull())
                .fetchOptional(this::toDomain);
    }

    @Override
    public Optional<Organization> findActiveBySlug(String slug) {
        return dsl.selectFrom(ORGANIZATIONS)
                .where(DSL.lower(ORGANIZATIONS.SLUG).eq(slug.toLowerCase()))
                .and(ORGANIZATIONS.DELETED_AT.isNull())
                .fetchOptional(this::toDomain);
    }

    @Override
    public List<Organization> listActiveByUserId(UUID userId) {
        return dsl.select(ORGANIZATIONS.fields())
                .from(ORGANIZATIONS)
                .join(ORG_MEMBERS).on(ORG_MEMBERS.ORG_ID.eq(ORGANIZATIONS.ID))
                .where(ORG_MEMBERS.USER_ID.eq(userId))
                .and(ORGANIZATIONS.DELETED_AT.isNull())
                .orderBy(ORGANIZATIONS.IS_PERSONAL.desc(), ORGANIZATIONS.CREATED_AT.asc())
                .fetchInto(OrganizationsRecord.class)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public PageResponse<Organization> listPage(OrgStatus statusFilter, PageQuery pageQuery) {
        Condition cond = ORGANIZATIONS.DELETED_AT.isNull();
        if (statusFilter != null) {
            cond = cond.and(ORGANIZATIONS.STATUS.eq(statusFilter.name()));
        }
        return PgJooqSupport.fetchPage(
                dsl,
                ORGANIZATIONS,
                cond,
                ORGANIZATIONS.CREATED_AT.desc(),
                pageQuery,
                this::toDomain
        );
    }

    @Override
    public boolean updateName(UUID orgId, String name, long expectedVersion, OffsetDateTime updatedAt) {
        int updated = dsl.update(ORGANIZATIONS)
                .set(ORGANIZATIONS.NAME, name)
                .set(ORGANIZATIONS.VERSION, ORGANIZATIONS.VERSION.plus(1))
                .set(ORGANIZATIONS.UPDATED_AT, updatedAt)
                .where(ORGANIZATIONS.ID.eq(orgId))
                .and(ORGANIZATIONS.VERSION.eq(expectedVersion))
                .and(ORGANIZATIONS.DELETED_AT.isNull())
                .execute();
        return updated == 1;
    }

    @Override
    public boolean updateStatus(UUID orgId, OrgStatus status, long expectedVersion, OffsetDateTime updatedAt) {
        int updated = dsl.update(ORGANIZATIONS)
                .set(ORGANIZATIONS.STATUS, status.name())
                .set(ORGANIZATIONS.VERSION, ORGANIZATIONS.VERSION.plus(1))
                .set(ORGANIZATIONS.UPDATED_AT, updatedAt)
                .where(ORGANIZATIONS.ID.eq(orgId))
                .and(ORGANIZATIONS.VERSION.eq(expectedVersion))
                .and(ORGANIZATIONS.DELETED_AT.isNull())
                .execute();
        return updated == 1;
    }

    private Organization toDomain(OrganizationsRecord r) {
        return new Organization(
                r.getId(),
                r.getName(),
                r.getSlug(),
                r.getOwnerUserId(),
                r.getTierId(),
                r.getDiscountRatioBps(),
                r.getMaxConcurrency(),
                Boolean.TRUE.equals(r.getIsPersonal()),
                PgJooqSupport.toEnum(OrgStatus.class, r.getStatus()),
                r.getDeletedAt(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}

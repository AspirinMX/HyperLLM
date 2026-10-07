package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.jooq.tables.records.OrgMembersRecord;
import app.pagefault.hyperllm.domain.model.enums.OrgRole;
import app.pagefault.hyperllm.domain.model.tenant.OrgMember;
import app.pagefault.hyperllm.domain.repository.OrgMemberRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.ORG_MEMBERS;

@Repository
@RequiredArgsConstructor
public class JooqOrgMemberRepository implements OrgMemberRepository {

    private final DSLContext dsl;

    @Override
    public void insert(OrgMember member) {
        dsl.insertInto(ORG_MEMBERS)
                .set(ORG_MEMBERS.ID, member.id())
                .set(ORG_MEMBERS.ORG_ID, member.orgId())
                .set(ORG_MEMBERS.USER_ID, member.userId())
                .set(ORG_MEMBERS.ROLE, member.role().name())
                .set(ORG_MEMBERS.JOINED_AT, member.joinedAt())
                .set(ORG_MEMBERS.UPDATED_AT, member.updatedAt())
                .execute();
    }

    @Override
    public Optional<OrgMember> findById(UUID memberId) {
        return dsl.selectFrom(ORG_MEMBERS)
                .where(ORG_MEMBERS.ID.eq(memberId))
                .fetchOptional(this::toDomain);
    }

    @Override
    public Optional<OrgMember> findByOrgAndUser(UUID orgId, UUID userId) {
        return dsl.selectFrom(ORG_MEMBERS)
                .where(ORG_MEMBERS.ORG_ID.eq(orgId))
                .and(ORG_MEMBERS.USER_ID.eq(userId))
                .fetchOptional(this::toDomain);
    }

    @Override
    public List<OrgMember> listByOrgId(UUID orgId) {
        return dsl.selectFrom(ORG_MEMBERS)
                .where(ORG_MEMBERS.ORG_ID.eq(orgId))
                .orderBy(ORG_MEMBERS.JOINED_AT.asc())
                .fetch(this::toDomain);
    }

    @Override
    public boolean updateRole(UUID memberId, OrgRole role, OffsetDateTime updatedAt) {
        int updated = dsl.update(ORG_MEMBERS)
                .set(ORG_MEMBERS.ROLE, role.name())
                .set(ORG_MEMBERS.UPDATED_AT, updatedAt)
                .where(ORG_MEMBERS.ID.eq(memberId))
                .execute();
        return updated == 1;
    }

    @Override
    public boolean deleteById(UUID memberId) {
        return dsl.deleteFrom(ORG_MEMBERS)
                .where(ORG_MEMBERS.ID.eq(memberId))
                .execute() == 1;
    }

    private OrgMember toDomain(OrgMembersRecord r) {
        return new OrgMember(
                r.getId(),
                r.getOrgId(),
                r.getUserId(),
                PgJooqSupport.toEnum(OrgRole.class, r.getRole()),
                r.getJoinedAt(),
                r.getUpdatedAt()
        );
    }
}

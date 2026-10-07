package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.domain.model.tenant.ProjectMember;
import app.pagefault.hyperllm.domain.repository.ProjectMemberRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.PROJECT_MEMBERS;

@Repository
@RequiredArgsConstructor
public class JooqProjectMemberRepository implements ProjectMemberRepository {

    private final DSLContext dsl;

    @Override
    public void insert(ProjectMember member) {
        dsl.insertInto(PROJECT_MEMBERS)
                .set(PROJECT_MEMBERS.ID, member.id())
                .set(PROJECT_MEMBERS.PROJECT_ID, member.projectId())
                .set(PROJECT_MEMBERS.ORG_ID, member.orgId())
                .set(PROJECT_MEMBERS.USER_ID, member.userId())
                .set(PROJECT_MEMBERS.GRANTED_BY_USER_ID, member.grantedByUserId())
                .set(PROJECT_MEMBERS.CREATED_AT, member.createdAt())
                .execute();
    }

    @Override
    public void replaceMembers(UUID projectId, UUID orgId, List<UUID> userIds, UUID grantedByUserId) {
        dsl.deleteFrom(PROJECT_MEMBERS)
                .where(PROJECT_MEMBERS.PROJECT_ID.eq(projectId))
                .execute();
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        var batch = userIds.stream().distinct().map(userId ->
                dsl.insertInto(PROJECT_MEMBERS)
                        .set(PROJECT_MEMBERS.ID, UuidV7.next())
                        .set(PROJECT_MEMBERS.PROJECT_ID, projectId)
                        .set(PROJECT_MEMBERS.ORG_ID, orgId)
                        .set(PROJECT_MEMBERS.USER_ID, userId)
                        .set(PROJECT_MEMBERS.GRANTED_BY_USER_ID, grantedByUserId)
                        .set(PROJECT_MEMBERS.CREATED_AT, now)
        ).toList();
        dsl.batch(batch).execute();
    }

    @Override
    public List<UUID> listUserIdsByProject(UUID projectId) {
        return dsl.select(PROJECT_MEMBERS.USER_ID)
                .from(PROJECT_MEMBERS)
                .where(PROJECT_MEMBERS.PROJECT_ID.eq(projectId))
                .fetch(PROJECT_MEMBERS.USER_ID);
    }

    @Override
    public boolean hasAccess(UUID projectId, UUID userId) {
        return dsl.fetchExists(
                dsl.selectOne()
                        .from(PROJECT_MEMBERS)
                        .where(PROJECT_MEMBERS.PROJECT_ID.eq(projectId))
                        .and(PROJECT_MEMBERS.USER_ID.eq(userId))
        );
    }
}

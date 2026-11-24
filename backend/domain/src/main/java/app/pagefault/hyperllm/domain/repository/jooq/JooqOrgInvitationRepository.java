package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.jooq.tables.records.OrgInvitationsRecord;
import app.pagefault.hyperllm.domain.model.enums.InvitationStatus;
import app.pagefault.hyperllm.domain.model.enums.OrgRole;
import app.pagefault.hyperllm.domain.model.tenant.OrgInvitation;
import app.pagefault.hyperllm.domain.repository.OrgInvitationRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.ORG_INVITATIONS;

@Repository
@RequiredArgsConstructor
public class JooqOrgInvitationRepository implements OrgInvitationRepository {

    private final DSLContext dsl;

    @Override
    public void insert(OrgInvitation invitation) {
        dsl.insertInto(ORG_INVITATIONS)
                .set(ORG_INVITATIONS.ID, invitation.id())
                .set(ORG_INVITATIONS.ORG_ID, invitation.orgId())
                .set(ORG_INVITATIONS.EMAIL, invitation.email())
                .set(ORG_INVITATIONS.ROLE, invitation.role().name())
                .set(ORG_INVITATIONS.PROJECT_IDS, PgJooqSupport.toUuidArray(invitation.projectIds()))
                .set(ORG_INVITATIONS.TOKEN_HASH, invitation.tokenHash())
                .set(ORG_INVITATIONS.INVITED_BY_USER_ID, invitation.invitedByUserId())
                .set(ORG_INVITATIONS.EXPIRES_AT, invitation.expiresAt())
                .set(ORG_INVITATIONS.ACCEPTED_AT, invitation.acceptedAt())
                .set(ORG_INVITATIONS.STATUS, invitation.status().name())
                .set(ORG_INVITATIONS.CREATED_AT, invitation.createdAt())
                .execute();
    }

    @Override
    public Optional<OrgInvitation> findByTokenHash(String tokenHash) {
        return dsl.selectFrom(ORG_INVITATIONS)
                .where(ORG_INVITATIONS.TOKEN_HASH.eq(tokenHash))
                .fetchOptional(this::toDomain);
    }

    @Override
    public List<OrgInvitation> listByOrgAndStatus(UUID orgId, InvitationStatus status) {
        return dsl.selectFrom(ORG_INVITATIONS)
                .where(ORG_INVITATIONS.ORG_ID.eq(orgId))
                .and(ORG_INVITATIONS.STATUS.eq(status.name()))
                .orderBy(ORG_INVITATIONS.CREATED_AT.desc())
                .fetch(this::toDomain);
    }

    @Override
    public boolean markAccepted(UUID invitationId, OffsetDateTime acceptedAt) {
        int updated = dsl.update(ORG_INVITATIONS)
                .set(ORG_INVITATIONS.STATUS, InvitationStatus.ACCEPTED.name())
                .set(ORG_INVITATIONS.ACCEPTED_AT, acceptedAt)
                .where(ORG_INVITATIONS.ID.eq(invitationId))
                .and(ORG_INVITATIONS.STATUS.eq(InvitationStatus.PENDING.name()))
                .execute();
        return updated == 1;
    }

    @Override
    public boolean revoke(UUID orgId, UUID invitationId) {
        int updated = dsl.update(ORG_INVITATIONS)
                .set(ORG_INVITATIONS.STATUS, InvitationStatus.REVOKED.name())
                .where(ORG_INVITATIONS.ID.eq(invitationId))
                .and(ORG_INVITATIONS.ORG_ID.eq(orgId))
                .and(ORG_INVITATIONS.STATUS.eq(InvitationStatus.PENDING.name()))
                .execute();
        return updated == 1;
    }

    private OrgInvitation toDomain(OrgInvitationsRecord r) {
        return new OrgInvitation(
                r.getId(),
                r.getOrgId(),
                r.getEmail(),
                PgJooqSupport.toEnum(OrgRole.class, r.getRole()),
                PgJooqSupport.fromUuidArray(r.getProjectIds()),
                r.getTokenHash(),
                r.getInvitedByUserId(),
                r.getExpiresAt(),
                r.getAcceptedAt(),
                PgJooqSupport.toEnum(InvitationStatus.class, r.getStatus()),
                r.getCreatedAt()
        );
    }
}

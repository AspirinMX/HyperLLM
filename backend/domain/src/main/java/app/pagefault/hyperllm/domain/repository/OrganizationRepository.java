package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import app.pagefault.hyperllm.domain.model.tenant.Organization;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository {

    void insert(Organization org);

    Optional<Organization> findById(UUID id);

    Optional<Organization> findActiveBySlug(String slug);

    List<Organization> listActiveByUserId(UUID userId);

    PageResponse<Organization> listPage(OrgStatus statusFilter, PageQuery pageQuery);

    boolean updateName(UUID orgId, String name, long expectedVersion, OffsetDateTime updatedAt);

    boolean updateStatus(UUID orgId, OrgStatus status, long expectedVersion, OffsetDateTime updatedAt);
}

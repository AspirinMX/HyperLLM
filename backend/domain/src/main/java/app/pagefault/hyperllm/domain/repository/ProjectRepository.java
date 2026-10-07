package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.enums.ProjectStatus;
import app.pagefault.hyperllm.domain.model.tenant.Project;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository {

    void insert(Project project);

    Optional<Project> findById(UUID id);

    PageResponse<Project> listByOrg(UUID orgId, PageQuery pageQuery);

    PageResponse<Project> listByOrgForMember(UUID orgId, UUID userId, PageQuery pageQuery);

    boolean updateSettings(
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
    );

    boolean softDelete(UUID projectId, long expectedVersion, OffsetDateTime deletedAt);
}

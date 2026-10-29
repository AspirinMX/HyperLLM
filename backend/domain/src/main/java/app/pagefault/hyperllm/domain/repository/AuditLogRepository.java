package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.billing.AuditLogEntry;

import java.util.Optional;
import java.util.UUID;

public interface AuditLogRepository {

    void insert(AuditLogEntry entry);

    Optional<UUID> findFirstResourceEventId(UUID orgId, String action, String resourceId);

    PageResponse<AuditLogEntry> listByOrg(UUID orgId, PageQuery pageQuery);
}

package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.billing.AuditLogEntry;

import java.util.UUID;

public interface AuditLogRepository {

    void insert(AuditLogEntry entry);

    PageResponse<AuditLogEntry> listByOrg(UUID orgId, PageQuery pageQuery);
}

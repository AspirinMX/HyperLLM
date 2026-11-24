package app.pagefault.hyperllm.admin.controller;

import app.pagefault.hyperllm.admin.dto.AdminDtos.AuditLogDto;
import app.pagefault.hyperllm.admin.web.AdminRequestContext;
import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.billing.AuditLogEntry;
import app.pagefault.hyperllm.domain.model.enums.AdminRole;
import app.pagefault.hyperllm.domain.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AuditLogRepository auditLogRepository;

    @GetMapping
    public PageResponse<AuditLogDto> listAuditLogs(
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request
    ) {
        AdminRequestContext.requireRole(
                request, AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.FINANCE, AdminRole.AUDITOR
        );
        PageResponse<AuditLogEntry> result = auditLogRepository.search(orgId, action, PageQuery.of(page, pageSize));
        return new PageResponse<>(
                result.items().stream().map(AdminAuditController::toDto).toList(),
                result.page(),
                result.pageSize(),
                result.total()
        );
    }

    private static AuditLogDto toDto(AuditLogEntry e) {
        return new AuditLogDto(
                e.id(),
                e.actorType(),
                e.actorId(),
                e.orgId(),
                e.action(),
                e.resourceType(),
                e.resourceId(),
                e.diffJson(),
                e.clientIp(),
                e.traceId(),
                e.createdAt()
        );
    }
}

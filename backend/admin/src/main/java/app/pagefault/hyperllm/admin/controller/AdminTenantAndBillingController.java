package app.pagefault.hyperllm.admin.controller;

import app.pagefault.hyperllm.admin.dto.AdminDtos.AdminOrgDto;
import app.pagefault.hyperllm.admin.dto.AdminDtos.AdminWalletTopupRequest;
import app.pagefault.hyperllm.admin.dto.AdminDtos.AdminWalletTopupResponse;
import app.pagefault.hyperllm.admin.dto.AdminDtos.UpdateOrgStatusRequest;
import app.pagefault.hyperllm.admin.service.AdminTenantAndBillingService;
import app.pagefault.hyperllm.admin.web.AdminPrincipal;
import app.pagefault.hyperllm.admin.web.AdminRequestContext;
import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.enums.AdminRole;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/orgs")
@RequiredArgsConstructor
public class AdminTenantAndBillingController {

    private final AdminTenantAndBillingService service;
    private final app.pagefault.hyperllm.admin.service.AdminWalletReadService walletReadService;

    @GetMapping
    public PageResponse<AdminOrgDto> listOrganizations(
            @RequestParam(required = false) OrgStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request
    ) {
        AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.FINANCE, AdminRole.AUDITOR);
        return service.listOrganizations(status, PageQuery.of(page, pageSize));
    }

    @GetMapping("/{orgId}")
    public AdminOrgDto getOrganization(@PathVariable UUID orgId, HttpServletRequest request) {
        AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.FINANCE, AdminRole.AUDITOR);
        return service.getOrganization(orgId);
    }

    /**
     * Live wallet of one tenant. A list cannot show this, because only the mirror knows what is
     * reserved; the detail view is where an operator looks before touching a balance.
     */
    @GetMapping("/{orgId}/wallet")
    public app.pagefault.hyperllm.admin.dto.AdminWalletDetailDto getWallet(
            @PathVariable UUID orgId,
            HttpServletRequest request
    ) {
        AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.FINANCE, AdminRole.AUDITOR);
        return walletReadService.read(orgId);
    }

    @PatchMapping("/{orgId}/status")
    public AdminOrgDto updateOrgStatus(
            @PathVariable UUID orgId,
            @Valid @RequestBody UpdateOrgStatusRequest body,
            HttpServletRequest request
    ) {
        AdminPrincipal principal = AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.FINANCE);
        return service.updateOrgStatus(orgId, principal.adminId(), AdminRequestContext.extractClientIp(request), body);
    }

    @PostMapping("/{orgId}/wallet/topup")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AdminWalletTopupResponse topupWallet(
            @PathVariable UUID orgId,
            @Valid @RequestBody AdminWalletTopupRequest body,
            HttpServletRequest request
    ) {
        AdminPrincipal principal = AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.FINANCE);
        return service.topupWallet(orgId, principal.adminId(), AdminRequestContext.extractClientIp(request), body);
    }
}

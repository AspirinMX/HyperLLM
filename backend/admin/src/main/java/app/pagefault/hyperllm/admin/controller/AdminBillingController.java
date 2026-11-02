package app.pagefault.hyperllm.admin.controller;

import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.CodeBatchResponse;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.CreateCodeBatchRequest;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.CreatePackSkuRequest;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.CreateVoucherRequest;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.PackSkuAdminDto;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.PaymentAnomalyDto;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.PaymentOrderAdminDto;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.RefundOrderRequest;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.ResolveAnomalyRequest;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.UpdatePackSkuRequest;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.UpdateVoucherStatusRequest;
import app.pagefault.hyperllm.admin.dto.AdminBillingDtos.VoucherAdminDto;
import app.pagefault.hyperllm.admin.service.AdminBillingService;
import app.pagefault.hyperllm.admin.web.AdminPrincipal;
import app.pagefault.hyperllm.admin.web.AdminRequestContext;
import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.enums.AdminRole;
import app.pagefault.hyperllm.domain.model.enums.PackSkuStatus;
import app.pagefault.hyperllm.domain.model.enums.PaymentAnomalyStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminBillingController {

    private final AdminBillingService service;

    // ---------------------------------------------------------------- pack SKUs

    @PostMapping("/resource-pack-skus")
    @ResponseStatus(HttpStatus.CREATED)
    public PackSkuAdminDto createSku(@Valid @RequestBody CreatePackSkuRequest body,
                                     HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.requireRole(request,
                AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.FINANCE);
        return service.createSku(principal.adminId(),
                AdminRequestContext.extractClientIp(request), body);
    }

    @PutMapping("/resource-pack-skus/{skuId}")
    public PackSkuAdminDto updateSku(@PathVariable UUID skuId,
                                     @Valid @RequestBody UpdatePackSkuRequest body,
                                     HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.requireRole(request,
                AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.FINANCE);
        return service.updateSku(principal.adminId(),
                AdminRequestContext.extractClientIp(request), skuId, body);
    }

    @GetMapping("/resource-pack-skus")
    public PageResponse<PackSkuAdminDto> listSkus(
            @RequestParam(required = false) PackSkuStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request
    ) {
        AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS,
                AdminRole.FINANCE, AdminRole.AUDITOR);
        return service.listSkus(status, PageQuery.of(page, pageSize));
    }

    // ---------------------------------------------------------------- vouchers

    @PostMapping("/vouchers")
    @ResponseStatus(HttpStatus.CREATED)
    public VoucherAdminDto createVoucher(@Valid @RequestBody CreateVoucherRequest body,
                                         HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.requireRole(request,
                AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.FINANCE);
        return service.createVoucher(principal.adminId(),
                AdminRequestContext.extractClientIp(request), body);
    }

    @GetMapping("/vouchers")
    public PageResponse<VoucherAdminDto> listVouchers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request
    ) {
        AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS,
                AdminRole.FINANCE, AdminRole.AUDITOR);
        return service.listVouchers(PageQuery.of(page, pageSize));
    }

    @PatchMapping("/vouchers/{voucherId}/status")
    public VoucherAdminDto updateVoucherStatus(@PathVariable UUID voucherId,
                                               @Valid @RequestBody UpdateVoucherStatusRequest body,
                                               HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.requireRole(request,
                AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.FINANCE);
        return service.updateVoucherStatus(principal.adminId(),
                AdminRequestContext.extractClientIp(request), voucherId, body);
    }

    @PostMapping("/vouchers/{voucherId}/code-batches")
    @ResponseStatus(HttpStatus.CREATED)
    public CodeBatchResponse createCodeBatch(@PathVariable UUID voucherId,
                                             @Valid @RequestBody CreateCodeBatchRequest body,
                                             HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.requireRole(request,
                AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.FINANCE);
        return service.createCodeBatch(principal.adminId(),
                AdminRequestContext.extractClientIp(request), voucherId, body.quantity());
    }

    // ---------------------------------------------------------------- payment operations

    @GetMapping("/payment-anomalies")
    public PageResponse<PaymentAnomalyDto> listAnomalies(
            @RequestParam(required = false) PaymentAnomalyStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request
    ) {
        AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS,
                AdminRole.FINANCE, AdminRole.AUDITOR);
        return service.listAnomalies(status, PageQuery.of(page, pageSize));
    }

    @PostMapping("/payment-anomalies/{anomalyId}/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resolveAnomaly(@PathVariable UUID anomalyId,
                               @Valid @RequestBody ResolveAnomalyRequest body,
                               HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.requireRole(request,
                AdminRole.SUPER_ADMIN, AdminRole.FINANCE);
        service.resolveAnomaly(principal.adminId(),
                AdminRequestContext.extractClientIp(request), anomalyId, body);
    }

    @PostMapping("/payment-orders/{orderId}/refund")
    public PaymentOrderAdminDto refundOrder(@PathVariable UUID orderId,
                                            @Valid @RequestBody RefundOrderRequest body,
                                            HttpServletRequest request) {
        AdminPrincipal principal = AdminRequestContext.requireRole(request,
                AdminRole.SUPER_ADMIN, AdminRole.FINANCE);
        return service.refundOrder(principal.adminId(),
                AdminRequestContext.extractClientIp(request), orderId, body);
    }

    @GetMapping("/orgs/{orgId}/payment-orders")
    public PageResponse<PaymentOrderAdminDto> listOrders(
            @PathVariable UUID orgId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request
    ) {
        AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS,
                AdminRole.FINANCE, AdminRole.AUDITOR);
        return service.listOrders(orgId, PageQuery.of(page, pageSize));
    }
}

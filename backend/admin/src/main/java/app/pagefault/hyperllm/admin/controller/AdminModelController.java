package app.pagefault.hyperllm.admin.controller;

import app.pagefault.hyperllm.admin.dto.AdminDtos.BindingDto;
import app.pagefault.hyperllm.admin.dto.AdminDtos.CreateVirtualModelRequest;
import app.pagefault.hyperllm.admin.dto.AdminDtos.PricingRuleDto;
import app.pagefault.hyperllm.admin.dto.AdminDtos.PublishPricingRuleRequest;
import app.pagefault.hyperllm.admin.dto.AdminDtos.ReplaceBindingsRequest;
import app.pagefault.hyperllm.admin.dto.AdminDtos.UpdateVirtualModelRequest;
import app.pagefault.hyperllm.admin.dto.AdminDtos.VirtualModelDto;
import app.pagefault.hyperllm.admin.service.AdminModelAndPricingService;
import app.pagefault.hyperllm.admin.web.AdminPrincipal;
import app.pagefault.hyperllm.admin.web.AdminRequestContext;
import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.domain.model.enums.AdminRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/models")
@RequiredArgsConstructor
public class AdminModelController {

    private final AdminModelAndPricingService service;

    @GetMapping
    public PageResponse<VirtualModelDto> listModels(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request
    ) {
        AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.FINANCE, AdminRole.AUDITOR);
        return service.listModels(PageQuery.of(page, pageSize));
    }

    @PostMapping
    public ResponseEntity<VirtualModelDto> createModel(
            @Valid @RequestBody CreateVirtualModelRequest body,
            HttpServletRequest request
    ) {
        AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createModel(body));
    }

    @PatchMapping("/{modelId}")
    public VirtualModelDto updateModel(
            @PathVariable UUID modelId,
            @Valid @RequestBody UpdateVirtualModelRequest body,
            HttpServletRequest request
    ) {
        AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS);
        return service.updateModel(modelId, body);
    }

    @DeleteMapping("/{modelId}")
    public ResponseEntity<Void> deleteModel(
            @PathVariable UUID modelId,
            @RequestParam long version,
            HttpServletRequest request
    ) {
        AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS);
        service.deleteModel(modelId, version);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{modelId}/bindings")
    public List<BindingDto> listBindings(@PathVariable UUID modelId, HttpServletRequest request) {
        AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.AUDITOR);
        return service.listBindings(modelId);
    }

    @PutMapping("/{modelId}/bindings")
    public List<BindingDto> replaceBindings(
            @PathVariable UUID modelId,
            @Valid @RequestBody ReplaceBindingsRequest body,
            HttpServletRequest request
    ) {
        AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS);
        return service.replaceBindings(modelId, body);
    }

    @DeleteMapping("/{modelId}/bindings")
    public ResponseEntity<Void> clearBindings(@PathVariable UUID modelId, HttpServletRequest request) {
        AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS);
        service.clearBindings(modelId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{modelId}/pricing-rules")
    public List<PricingRuleDto> listPricingRules(@PathVariable UUID modelId, HttpServletRequest request) {
        AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.FINANCE, AdminRole.AUDITOR);
        return service.listPricingRules(modelId);
    }

    @PostMapping("/{modelId}/pricing-rules")
    public ResponseEntity<PricingRuleDto> publishPricingRule(
            @PathVariable UUID modelId,
            @Valid @RequestBody PublishPricingRuleRequest body,
            HttpServletRequest request
    ) {
        AdminPrincipal principal = AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.FINANCE);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.publishPricingRule(modelId, principal.adminId(), body));
    }
}

package app.pagefault.hyperllm.admin.controller;

import app.pagefault.hyperllm.admin.reconcile.ReconciliationJob;
import app.pagefault.hyperllm.admin.reconcile.ReconciliationService;
import app.pagefault.hyperllm.admin.reconcile.ReconciliationService.Finding;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/reconciliation")
@RequiredArgsConstructor
public class AdminReconciliationController {

    public record ResolveRequest(String note) {
    }

    public record BudgetResolutionRequest(long confirmedMicros) {
    }

    private final ReconciliationService service;
    private final ReconciliationJob job;
    private final ProjectRepository projectRepository;

    @GetMapping("/findings")
    public List<Finding> findings(
            @RequestParam(defaultValue = "OPEN") String state,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return service.list(state, limit);
    }

    @PostMapping("/findings/{id}/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resolve(@PathVariable UUID id, @RequestBody(required = false) ResolveRequest body) {
        service.resolve(id, body == null ? null : body.note());
    }

    @PostMapping("/run")
    public Map<String, Object> run() {
        int checked = job.reconcileOnce(200);
        return Map.of("checkedOrganisations", checked);
    }

    @PostMapping("/orgs/{orgId}/projects/{projectId}/budget/{month}/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resolveBudget(
            @PathVariable UUID orgId,
            @PathVariable UUID projectId,
            @PathVariable String month,
            @RequestBody BudgetResolutionRequest body
    ) {
        var project = projectRepository.findById(projectId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.TENANT_PROJECT_NOT_FOUND));
        if (!project.orgId().equals(orgId)) {
            throw new HyperLlmException(ErrorCode.TENANT_PROJECT_NOT_FOUND);
        }
        YearMonth parsed;
        try {
            parsed = YearMonth.parse(month);
        } catch (RuntimeException ex) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "The month must be YYYY-MM");
        }
        service.resolveBudgetMonth(projectId, parsed.atDay(1), body.confirmedMicros());
    }
}

package app.pagefault.hyperllm.console.controller;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.console.dto.TenantDtos.CreateProjectRequest;
import app.pagefault.hyperllm.console.dto.TenantDtos.ProjectDto;
import app.pagefault.hyperllm.console.dto.TenantDtos.ReplaceProjectMembersRequest;
import app.pagefault.hyperllm.console.dto.TenantDtos.UpdateProjectRequest;
import app.pagefault.hyperllm.console.service.ConsoleProjectAndKeyService;
import app.pagefault.hyperllm.console.web.ConsoleRequestContext;
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
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ConsoleProjectController {

    private final ConsoleProjectAndKeyService service;

    @GetMapping("/orgs/{orgId}/projects")
    public PageResponse<ProjectDto> listProjects(
            @PathVariable UUID orgId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request
    ) {
        return service.listProjects(orgId, ConsoleRequestContext.requirePrincipal(request).userId(), PageQuery.of(page, pageSize));
    }

    @PostMapping("/orgs/{orgId}/projects")
    public ResponseEntity<ProjectDto> createProject(
            @PathVariable UUID orgId,
            @Valid @RequestBody CreateProjectRequest body,
            HttpServletRequest request
    ) {
        ProjectDto created = service.createProject(orgId, ConsoleRequestContext.requirePrincipal(request).userId(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/projects/{projectId}")
    public ProjectDto getProject(@PathVariable UUID projectId, HttpServletRequest request) {
        return service.getProject(projectId, ConsoleRequestContext.requirePrincipal(request).userId());
    }

    @PatchMapping("/projects/{projectId}")
    public ProjectDto updateProject(
            @PathVariable UUID projectId,
            @Valid @RequestBody UpdateProjectRequest body,
            HttpServletRequest request
    ) {
        return service.updateProject(projectId, ConsoleRequestContext.requirePrincipal(request).userId(), body);
    }

    @DeleteMapping("/projects/{projectId}")
    public ResponseEntity<Void> deleteProject(
            @PathVariable UUID projectId,
            @RequestParam long version,
            HttpServletRequest request
    ) {
        service.deleteProject(projectId, ConsoleRequestContext.requirePrincipal(request).userId(), version);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/projects/{projectId}/members")
    public List<UUID> replaceProjectMembers(
            @PathVariable UUID projectId,
            @Valid @RequestBody ReplaceProjectMembersRequest body,
            HttpServletRequest request
    ) {
        return service.replaceProjectMembers(projectId, ConsoleRequestContext.requirePrincipal(request).userId(), body);
    }
}

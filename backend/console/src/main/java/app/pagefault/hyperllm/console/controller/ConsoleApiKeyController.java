package app.pagefault.hyperllm.console.controller;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import app.pagefault.hyperllm.console.dto.TenantDtos.ApiKeyDto;
import app.pagefault.hyperllm.console.dto.TenantDtos.CreateApiKeyRequest;
import app.pagefault.hyperllm.console.dto.TenantDtos.CreatedApiKeyDto;
import app.pagefault.hyperllm.console.dto.TenantDtos.UpdateApiKeyRequest;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ConsoleApiKeyController {

    private final ConsoleProjectAndKeyService service;

    @GetMapping("/projects/{projectId}/api-keys")
    public PageResponse<ApiKeyDto> listApiKeys(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request
    ) {
        return service.listApiKeys(projectId, ConsoleRequestContext.requirePrincipal(request).userId(), PageQuery.of(page, pageSize));
    }

    @PostMapping("/projects/{projectId}/api-keys")
    public ResponseEntity<CreatedApiKeyDto> createApiKey(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateApiKeyRequest body,
            HttpServletRequest request
    ) {
        CreatedApiKeyDto created = service.createApiKey(projectId, ConsoleRequestContext.requirePrincipal(request).userId(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/api-keys/{keyId}")
    public ApiKeyDto updateApiKey(
            @PathVariable UUID keyId,
            @Valid @RequestBody UpdateApiKeyRequest body,
            HttpServletRequest request
    ) {
        return service.updateApiKey(keyId, ConsoleRequestContext.requirePrincipal(request).userId(), body);
    }

    @DeleteMapping("/api-keys/{keyId}")
    public ResponseEntity<Void> revokeApiKey(
            @PathVariable UUID keyId,
            @RequestParam long version,
            HttpServletRequest request
    ) {
        service.revokeApiKey(keyId, ConsoleRequestContext.requirePrincipal(request).userId(), version);
        return ResponseEntity.noContent().build();
    }
}

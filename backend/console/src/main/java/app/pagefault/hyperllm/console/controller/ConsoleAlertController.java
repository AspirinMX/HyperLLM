package app.pagefault.hyperllm.console.controller;

import app.pagefault.hyperllm.console.dto.AlertDtos.AlertRuleDto;
import app.pagefault.hyperllm.console.dto.AlertDtos.CreateAlertRuleRequest;
import app.pagefault.hyperllm.console.dto.AlertDtos.CreateEndpointRequest;
import app.pagefault.hyperllm.console.dto.AlertDtos.CreatedEndpointDto;
import app.pagefault.hyperllm.console.dto.AlertDtos.EndpointDto;
import app.pagefault.hyperllm.console.dto.AlertDtos.UpdateEndpointRequest;
import app.pagefault.hyperllm.console.service.ConsoleAlertService;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ConsoleAlertController {

    private final ConsoleAlertService service;

    @GetMapping("/orgs/{orgId}/notification-endpoints")
    public List<EndpointDto> listEndpoints(@PathVariable UUID orgId, HttpServletRequest request) {
        return service.listEndpoints(orgId, ConsoleRequestContext.requirePrincipal(request).userId());
    }

    @PostMapping("/orgs/{orgId}/notification-endpoints")
    public ResponseEntity<CreatedEndpointDto> createEndpoint(
            @PathVariable UUID orgId,
            @Valid @RequestBody CreateEndpointRequest body,
            HttpServletRequest request
    ) {
        CreatedEndpointDto created = service.createEndpoint(
                orgId, ConsoleRequestContext.requirePrincipal(request).userId(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/orgs/{orgId}/notification-endpoints/{endpointId}")
    public EndpointDto updateEndpoint(
            @PathVariable UUID orgId,
            @PathVariable UUID endpointId,
            @Valid @RequestBody UpdateEndpointRequest body,
            HttpServletRequest request
    ) {
        return service.updateEndpoint(orgId, ConsoleRequestContext.requirePrincipal(request).userId(),
                endpointId, body);
    }

    @DeleteMapping("/orgs/{orgId}/notification-endpoints/{endpointId}")
    public ResponseEntity<Void> deleteEndpoint(
            @PathVariable UUID orgId,
            @PathVariable UUID endpointId,
            @RequestParam long version,
            HttpServletRequest request
    ) {
        service.deleteEndpoint(orgId, ConsoleRequestContext.requirePrincipal(request).userId(),
                endpointId, version);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/orgs/{orgId}/alert-rules")
    public List<AlertRuleDto> listRules(@PathVariable UUID orgId, HttpServletRequest request) {
        return service.listRules(orgId, ConsoleRequestContext.requirePrincipal(request).userId());
    }

    @PostMapping("/orgs/{orgId}/alert-rules")
    public ResponseEntity<AlertRuleDto> createRule(
            @PathVariable UUID orgId,
            @Valid @RequestBody CreateAlertRuleRequest body,
            HttpServletRequest request
    ) {
        AlertRuleDto created = service.createRule(
                orgId, ConsoleRequestContext.requirePrincipal(request).userId(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/orgs/{orgId}/alert-rules/{ruleId}")
    public ResponseEntity<Void> deleteRule(
            @PathVariable UUID orgId,
            @PathVariable UUID ruleId,
            @RequestParam long version,
            HttpServletRequest request
    ) {
        service.deleteRule(orgId, ConsoleRequestContext.requirePrincipal(request).userId(), ruleId, version);
        return ResponseEntity.noContent().build();
    }
}

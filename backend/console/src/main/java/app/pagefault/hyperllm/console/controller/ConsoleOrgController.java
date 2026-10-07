package app.pagefault.hyperllm.console.controller;

import app.pagefault.hyperllm.console.dto.TenantDtos.AcceptInvitationRequest;
import app.pagefault.hyperllm.console.dto.TenantDtos.CreateInvitationRequest;
import app.pagefault.hyperllm.console.dto.TenantDtos.CreateOrgRequest;
import app.pagefault.hyperllm.console.dto.TenantDtos.InvitationResponseDto;
import app.pagefault.hyperllm.console.dto.TenantDtos.OrgDto;
import app.pagefault.hyperllm.console.dto.TenantDtos.OrgMemberDto;
import app.pagefault.hyperllm.console.dto.TenantDtos.UpdateOrgMemberRoleRequest;
import app.pagefault.hyperllm.console.dto.TenantDtos.UpdateOrgRequest;
import app.pagefault.hyperllm.console.service.ConsoleOrgService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ConsoleOrgController {

    private final ConsoleOrgService orgService;

    @GetMapping("/orgs")
    public List<OrgDto> listOrgs(HttpServletRequest request) {
        return orgService.listUserOrgs(ConsoleRequestContext.requirePrincipal(request).userId());
    }

    @PostMapping("/orgs")
    public ResponseEntity<OrgDto> createOrg(@Valid @RequestBody CreateOrgRequest body, HttpServletRequest request) {
        OrgDto created = orgService.createOrg(ConsoleRequestContext.requirePrincipal(request).userId(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/orgs/{orgId}")
    public OrgDto getOrg(@PathVariable UUID orgId, HttpServletRequest request) {
        return orgService.getOrg(orgId, ConsoleRequestContext.requirePrincipal(request).userId());
    }

    @PatchMapping("/orgs/{orgId}")
    public OrgDto updateOrg(
            @PathVariable UUID orgId,
            @Valid @RequestBody UpdateOrgRequest body,
            HttpServletRequest request
    ) {
        return orgService.updateOrg(orgId, ConsoleRequestContext.requirePrincipal(request).userId(), body);
    }

    @GetMapping("/orgs/{orgId}/members")
    public List<OrgMemberDto> listMembers(@PathVariable UUID orgId, HttpServletRequest request) {
        return orgService.listMembers(orgId, ConsoleRequestContext.requirePrincipal(request).userId());
    }

    @PatchMapping("/orgs/{orgId}/members/{memberId}")
    public OrgMemberDto updateMemberRole(
            @PathVariable UUID orgId,
            @PathVariable UUID memberId,
            @Valid @RequestBody UpdateOrgMemberRoleRequest body,
            HttpServletRequest request
    ) {
        return orgService.updateMemberRole(orgId, memberId, ConsoleRequestContext.requirePrincipal(request).userId(), body);
    }

    @DeleteMapping("/orgs/{orgId}/members/{memberId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable UUID orgId,
            @PathVariable UUID memberId,
            HttpServletRequest request
    ) {
        orgService.removeMember(orgId, memberId, ConsoleRequestContext.requirePrincipal(request).userId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/orgs/{orgId}/invitations")
    public ResponseEntity<InvitationResponseDto> createInvitation(
            @PathVariable UUID orgId,
            @Valid @RequestBody CreateInvitationRequest body,
            HttpServletRequest request
    ) {
        InvitationResponseDto dto = orgService.createInvitation(
                orgId, ConsoleRequestContext.requirePrincipal(request).userId(), body
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PostMapping("/invitations/accept")
    public OrgDto acceptInvitation(
            @Valid @RequestBody AcceptInvitationRequest body,
            HttpServletRequest request
    ) {
        return orgService.acceptInvitation(ConsoleRequestContext.requirePrincipal(request).userId(), body);
    }
}

package app.pagefault.hyperllm.admin.controller;

import app.pagefault.hyperllm.admin.dto.AdminDtos.ChannelDto;
import app.pagefault.hyperllm.admin.dto.AdminDtos.ChannelProbeDto;
import app.pagefault.hyperllm.admin.dto.AdminDtos.CreateChannelRequest;
import app.pagefault.hyperllm.admin.dto.AdminDtos.ProviderDto;
import app.pagefault.hyperllm.admin.dto.AdminDtos.UpdateChannelRequest;
import app.pagefault.hyperllm.admin.service.AdminChannelService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminChannelController {

    private final AdminChannelService service;

    @GetMapping("/providers")
    public List<ProviderDto> listProviders(HttpServletRequest request) {
        AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.AUDITOR);
        return service.listProviders();
    }

    @GetMapping("/channels")
    public PageResponse<ChannelDto> listChannels(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request
    ) {
        AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.AUDITOR);
        return service.listChannels(PageQuery.of(page, pageSize));
    }

    @GetMapping("/channels/{channelId}")
    public ChannelDto getChannel(@PathVariable UUID channelId, HttpServletRequest request) {
        AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS, AdminRole.AUDITOR);
        return service.getChannel(channelId);
    }

    @PostMapping("/channels")
    public ResponseEntity<ChannelDto> createChannel(
            @Valid @RequestBody CreateChannelRequest body,
            HttpServletRequest request
    ) {
        AdminPrincipal principal = AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS);
        ChannelDto created = service.createChannel(
                principal.adminId(), AdminRequestContext.extractClientIp(request), body
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/channels/{channelId}")
    public ChannelDto updateChannel(
            @PathVariable UUID channelId,
            @Valid @RequestBody UpdateChannelRequest body,
            HttpServletRequest request
    ) {
        AdminPrincipal principal = AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS);
        return service.updateChannel(channelId, principal.adminId(), AdminRequestContext.extractClientIp(request), body);
    }

    @DeleteMapping("/channels/{channelId}")
    public ResponseEntity<Void> deleteChannel(
            @PathVariable UUID channelId,
            @RequestParam long version,
            HttpServletRequest request
    ) {
        AdminPrincipal principal = AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS);
        service.deleteChannel(channelId, version, principal.adminId(), AdminRequestContext.extractClientIp(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/channels/{channelId}/probe")
    public ChannelProbeDto probeChannel(@PathVariable UUID channelId, HttpServletRequest request) {
        AdminRequestContext.requireRole(request, AdminRole.SUPER_ADMIN, AdminRole.OPS);
        return service.probeChannel(channelId);
    }
}

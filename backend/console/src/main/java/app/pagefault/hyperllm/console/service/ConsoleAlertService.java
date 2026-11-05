package app.pagefault.hyperllm.console.service;

import app.pagefault.hyperllm.common.crypto.AesGcmCipher;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.console.config.ConsoleProperties;
import app.pagefault.hyperllm.console.dto.AlertDtos.AlertRuleDto;
import app.pagefault.hyperllm.console.dto.AlertDtos.CreateAlertRuleRequest;
import app.pagefault.hyperllm.console.dto.AlertDtos.CreateEndpointRequest;
import app.pagefault.hyperllm.console.dto.AlertDtos.CreatedEndpointDto;
import app.pagefault.hyperllm.console.dto.AlertDtos.EndpointDto;
import app.pagefault.hyperllm.console.dto.AlertDtos.UpdateEndpointRequest;
import app.pagefault.hyperllm.domain.model.billing.BillingAlertRule;
import app.pagefault.hyperllm.domain.model.billing.NotificationEndpoint;
import app.pagefault.hyperllm.domain.model.enums.AlertType;
import app.pagefault.hyperllm.domain.model.enums.NotificationChannelType;
import app.pagefault.hyperllm.domain.model.enums.OrgRole;
import app.pagefault.hyperllm.domain.model.enums.ToggleStatus;
import app.pagefault.hyperllm.domain.model.tenant.Project;
import app.pagefault.hyperllm.domain.repository.BillingAlertRuleRepository;
import app.pagefault.hyperllm.domain.repository.NotificationEndpointRepository;
import app.pagefault.hyperllm.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Organisation-owned alert configuration.
 *
 * <p>A rule and every endpoint it references must belong to the same organisation; a project rule
 * must point at one of that organisation's projects. Webhook endpoints get a generated signing
 * secret, returned once and stored encrypted with the endpoint id as additional data. Endpoints
 * can only be removed when no active rule still references them, so a rule never silently loses its
 * delivery target.
 */
@Service
@RequiredArgsConstructor
public class ConsoleAlertService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConsoleRbacService rbac;
    private final ConsoleProperties properties;
    private final AesGcmCipher cipher;
    private final NotificationEndpointRepository endpointRepository;
    private final BillingAlertRuleRepository ruleRepository;
    private final ProjectRepository projectRepository;

    public List<EndpointDto> listEndpoints(UUID orgId, UUID userId) {
        rbac.requireOrgMembership(orgId, userId);
        return endpointRepository.listActiveByOrg(orgId).stream().map(ConsoleAlertService::toDto).toList();
    }

    @Transactional
    public CreatedEndpointDto createEndpoint(UUID orgId, UUID userId, CreateEndpointRequest request) {
        rbac.requireOrgRole(orgId, userId, OrgRole.OWNER, OrgRole.ADMIN);
        String target = request.target().trim();
        validateTarget(request.channelType(), target);
        UUID id = UuidV7.next();
        String rawSecret = null;
        byte[] encrypted = null;
        if (request.channelType() == NotificationChannelType.WEBHOOK) {
            rawSecret = newSecret();
            encrypted = cipher.encryptUtf8(rawSecret, id.toString());
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        NotificationEndpoint endpoint = new NotificationEndpoint(id, orgId, request.channelType(), target,
                encrypted, ToggleStatus.ACTIVE, null, 0L, now, now);
        endpointRepository.insert(endpoint);
        return new CreatedEndpointDto(toDto(endpoint), rawSecret);
    }

    @Transactional
    public EndpointDto updateEndpoint(UUID orgId, UUID userId, UUID endpointId, UpdateEndpointRequest request) {
        rbac.requireOrgRole(orgId, userId, OrgRole.OWNER, OrgRole.ADMIN);
        NotificationEndpoint existing = requireEndpoint(orgId, endpointId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        NotificationEndpoint next = new NotificationEndpoint(existing.id(), existing.orgId(),
                existing.channelType(), existing.target(), existing.encryptedSigningSecret(),
                request.status(), existing.deletedAt(), existing.version(), existing.createdAt(), now);
        if (!endpointRepository.update(next, request.version())) {
            throw new HyperLlmException(ErrorCode.CONFLICT_OPTIMISTIC_LOCK);
        }
        return toDto(endpointRepository.findById(endpointId).orElseThrow());
    }

    @Transactional
    public void deleteEndpoint(UUID orgId, UUID userId, UUID endpointId, long version) {
        rbac.requireOrgRole(orgId, userId, OrgRole.OWNER, OrgRole.ADMIN);
        requireEndpoint(orgId, endpointId);
        boolean referenced = ruleRepository.listActiveByOrg(orgId).stream()
                .anyMatch(rule -> rule.endpointIds().contains(endpointId));
        if (referenced) {
            throw new HyperLlmException(ErrorCode.NOTIFICATION_ENDPOINT_IN_USE);
        }
        if (!endpointRepository.softDelete(endpointId, version, OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new HyperLlmException(ErrorCode.CONFLICT_OPTIMISTIC_LOCK);
        }
    }

    public List<AlertRuleDto> listRules(UUID orgId, UUID userId) {
        rbac.requireOrgMembership(orgId, userId);
        return ruleRepository.listActiveByOrg(orgId).stream().map(ConsoleAlertService::toDto).toList();
    }

    @Transactional
    public AlertRuleDto createRule(UUID orgId, UUID userId, CreateAlertRuleRequest request) {
        rbac.requireOrgRole(orgId, userId, OrgRole.OWNER, OrgRole.ADMIN);
        List<UUID> endpointIds = request.endpointIds().stream().distinct().toList();
        Set<UUID> owned = new HashSet<>(endpointRepository.listByIds(endpointIds).stream()
                .filter(endpoint -> endpoint.orgId().equals(orgId) && endpoint.status() == ToggleStatus.ACTIVE)
                .map(NotificationEndpoint::id)
                .toList());
        if (!owned.containsAll(endpointIds)) {
            throw new HyperLlmException(ErrorCode.NOTIFICATION_ENDPOINT_NOT_FOUND);
        }

        long threshold = request.thresholdMicros();
        if (request.alertType() == AlertType.LOW_BALANCE) {
            if (request.projectId() != null) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST,
                        "A low-balance rule applies to the whole organisation");
            }
        } else {
            if (request.projectId() == null) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST,
                        "A budget rule needs a project");
            }
            Project project = projectRepository.findById(request.projectId())
                    .orElseThrow(() -> new HyperLlmException(ErrorCode.TENANT_PROJECT_NOT_FOUND));
            if (!project.orgId().equals(orgId)) {
                throw new HyperLlmException(ErrorCode.TENANT_PROJECT_NOT_FOUND);
            }
            Long cap = request.alertType() == AlertType.PROJECT_SOFT_CAP
                    ? project.monthlySoftCapMicros() : project.monthlyHardCapMicros();
            if (cap == null) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST,
                        "The project has no cap of that kind");
            }
            // The project's cap is authoritative; the stored threshold mirrors it for display.
            threshold = cap;
        }
        boolean duplicate = ruleRepository.listActiveByOrg(orgId).stream()
                .anyMatch(rule -> rule.alertType() == request.alertType()
                        && java.util.Objects.equals(rule.projectId(), request.projectId()));
        if (duplicate) {
            throw new HyperLlmException(ErrorCode.ALERT_RULE_EXISTS);
        }

        UUID id = UuidV7.next();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        BillingAlertRule rule = new BillingAlertRule(id, orgId, request.projectId(), request.alertType(),
                threshold, endpointIds, request.cooldownMinutes() == null ? 60 : request.cooldownMinutes(),
                null, ToggleStatus.ACTIVE, null, 0L, now, now);
        ruleRepository.insert(rule);
        return toDto(rule);
    }

    @Transactional
    public void deleteRule(UUID orgId, UUID userId, UUID ruleId, long version) {
        rbac.requireOrgRole(orgId, userId, OrgRole.OWNER, OrgRole.ADMIN);
        BillingAlertRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.ALERT_RULE_NOT_FOUND));
        if (!rule.orgId().equals(orgId) || rule.deletedAt() != null) {
            throw new HyperLlmException(ErrorCode.ALERT_RULE_NOT_FOUND);
        }
        if (!ruleRepository.softDelete(ruleId, version, OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new HyperLlmException(ErrorCode.CONFLICT_OPTIMISTIC_LOCK);
        }
    }

    private NotificationEndpoint requireEndpoint(UUID orgId, UUID endpointId) {
        NotificationEndpoint endpoint = endpointRepository.findById(endpointId)
                .orElseThrow(() -> new HyperLlmException(ErrorCode.NOTIFICATION_ENDPOINT_NOT_FOUND));
        if (!endpoint.orgId().equals(orgId) || endpoint.deletedAt() != null) {
            throw new HyperLlmException(ErrorCode.NOTIFICATION_ENDPOINT_NOT_FOUND);
        }
        return endpoint;
    }

    private void validateTarget(NotificationChannelType type, String target) {
        if (type == NotificationChannelType.EMAIL) {
            if (!target.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "The email address is invalid");
            }
            return;
        }
        try {
            URI uri = new URI(target);
            boolean schemeOk = "https".equalsIgnoreCase(uri.getScheme())
                    || (properties.getAlerts().isAllowHttp() && "http".equalsIgnoreCase(uri.getScheme()));
            if (!schemeOk || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST,
                        "A webhook target must be an HTTPS URL without credentials");
            }
        } catch (URISyntaxException ex) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "The webhook URL is invalid");
        }
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static EndpointDto toDto(NotificationEndpoint endpoint) {
        return new EndpointDto(endpoint.id(), endpoint.orgId(), endpoint.channelType(), endpoint.target(),
                endpoint.status(), endpoint.version(), endpoint.createdAt(), endpoint.updatedAt());
    }

    private static AlertRuleDto toDto(BillingAlertRule rule) {
        return new AlertRuleDto(rule.id(), rule.orgId(), rule.projectId(), rule.alertType(),
                rule.thresholdMicros(), rule.endpointIds(), rule.cooldownMinutes(),
                rule.lastTriggeredAt(), rule.status(), rule.version(), rule.createdAt(), rule.updatedAt());
    }
}

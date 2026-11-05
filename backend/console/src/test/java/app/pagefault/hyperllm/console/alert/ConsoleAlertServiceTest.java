package app.pagefault.hyperllm.console.alert;

import app.pagefault.hyperllm.common.crypto.AesGcmCipher;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.console.config.ConsoleProperties;
import app.pagefault.hyperllm.console.dto.AlertDtos.AlertRuleDto;
import app.pagefault.hyperllm.console.dto.AlertDtos.CreateAlertRuleRequest;
import app.pagefault.hyperllm.console.dto.AlertDtos.CreateEndpointRequest;
import app.pagefault.hyperllm.console.dto.AlertDtos.CreatedEndpointDto;
import app.pagefault.hyperllm.console.service.ConsoleAlertService;
import app.pagefault.hyperllm.console.service.ConsoleRbacService;
import app.pagefault.hyperllm.domain.model.billing.BillingAlertRule;
import app.pagefault.hyperllm.domain.model.billing.NotificationEndpoint;
import app.pagefault.hyperllm.domain.model.enums.AlertType;
import app.pagefault.hyperllm.domain.model.enums.NotificationChannelType;
import app.pagefault.hyperllm.domain.model.enums.OrgRole;
import app.pagefault.hyperllm.domain.model.enums.ProjectEnvironment;
import app.pagefault.hyperllm.domain.model.enums.ProjectStatus;
import app.pagefault.hyperllm.domain.model.enums.ToggleStatus;
import app.pagefault.hyperllm.domain.model.tenant.OrgMember;
import app.pagefault.hyperllm.domain.model.tenant.Project;
import app.pagefault.hyperllm.domain.repository.BillingAlertRuleRepository;
import app.pagefault.hyperllm.domain.repository.NotificationEndpointRepository;
import app.pagefault.hyperllm.domain.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The console owns validation and signing secrets; the delivery path is covered by AlertingIT. */
class ConsoleAlertServiceTest {

    private static final AesGcmCipher CIPHER =
            AesGcmCipher.fromBase64((byte) 1, "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");

    private final ConsoleRbacService rbac = mock(ConsoleRbacService.class);
    private final NotificationEndpointRepository endpoints = mock(NotificationEndpointRepository.class);
    private final BillingAlertRuleRepository rules = mock(BillingAlertRuleRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);

    private ConsoleAlertService service;
    private UUID orgId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        ConsoleProperties properties = new ConsoleProperties();
        properties.getAlerts().setAllowHttp(false);
        service = new ConsoleAlertService(rbac, properties, CIPHER, endpoints, rules, projects);
        orgId = UUID.randomUUID();
        userId = UUID.randomUUID();
        OrgMember member = new OrgMember(UUID.randomUUID(), orgId, userId, OrgRole.OWNER,
                OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC));
        when(rbac.requireOrgMembership(orgId, userId)).thenReturn(member);
        when(rbac.requireOrgRole(orgId, userId, OrgRole.OWNER, OrgRole.ADMIN)).thenReturn(member);
    }

    @Test
    void createsAWebhookWithASecretThatOnlyDecryptsForThatEndpoint() {
        CreatedEndpointDto created = service.createEndpoint(orgId, userId,
                new CreateEndpointRequest(NotificationChannelType.WEBHOOK, "https://example.com/hook"));

        assertThat(created.signingSecret()).isNotBlank();
        ArgumentCaptor<NotificationEndpoint> captor = ArgumentCaptor.forClass(NotificationEndpoint.class);
        verify(endpoints).insert(captor.capture());
        NotificationEndpoint stored = captor.getValue();
        assertThat(CIPHER.decryptUtf8(stored.encryptedSigningSecret(), stored.id().toString()))
                .isEqualTo(created.signingSecret());
        assertThatThrownBy(() -> CIPHER.decryptUtf8(stored.encryptedSigningSecret(),
                UUID.randomUUID().toString()))
                .as("the secret is bound to the endpoint id")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAPlainHttpWebhook() {
        assertThatThrownBy(() -> service.createEndpoint(orgId, userId,
                new CreateEndpointRequest(NotificationChannelType.WEBHOOK, "http://example.com/hook")))
                .isInstanceOfSatisfying(HyperLlmException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PROTOCOL_INVALID_REQUEST));
    }

    @Test
    void aRuleCannotReferenceAnotherOrganisationsEndpoint() {
        UUID foreignEndpoint = UUID.randomUUID();
        when(endpoints.listByIds(any())).thenReturn(List.of(new NotificationEndpoint(foreignEndpoint,
                UUID.randomUUID(), NotificationChannelType.WEBHOOK, "https://elsewhere.example/hook",
                null, ToggleStatus.ACTIVE, null, 0L, OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC))));

        assertThatThrownBy(() -> service.createRule(orgId, userId, new CreateAlertRuleRequest(
                AlertType.LOW_BALANCE, null, 100L, List.of(foreignEndpoint), 60)))
                .isInstanceOfSatisfying(HyperLlmException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_ENDPOINT_NOT_FOUND));
    }

    @Test
    void aBudgetRuleTakesItsThresholdFromTheProjectCapAndRejectsAMissingOne() {
        UUID projectId = UUID.randomUUID();
        Project project = new Project(projectId, orgId, "p", ProjectEnvironment.PRODUCTION,
                null, 500L, null, null, null, null, false, (short) 7, null,
                ProjectStatus.ACTIVE, null, 0L, OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC));
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        UUID endpointId = UUID.randomUUID();
        when(endpoints.listByIds(any())).thenReturn(List.of(new NotificationEndpoint(endpointId, orgId,
                NotificationChannelType.EMAIL, "ops@example.com", null, ToggleStatus.ACTIVE, null,
                0L, OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC))));
        when(rules.listActiveByOrg(orgId)).thenReturn(List.of());

        AlertRuleDto created = service.createRule(orgId, userId, new CreateAlertRuleRequest(
                AlertType.PROJECT_SOFT_CAP, projectId, 1L, List.of(endpointId), 30));
        assertThat(created.thresholdMicros()).isEqualTo(500L);

        Project noCap = new Project(projectId, orgId, "p", ProjectEnvironment.PRODUCTION,
                null, null, null, null, null, null, false, (short) 7, null,
                ProjectStatus.ACTIVE, null, 0L, OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC));
        when(projects.findById(projectId)).thenReturn(Optional.of(noCap));
        assertThatThrownBy(() -> service.createRule(orgId, userId, new CreateAlertRuleRequest(
                AlertType.PROJECT_SOFT_CAP, projectId, 1L, List.of(endpointId), 30)))
                .isInstanceOfSatisfying(HyperLlmException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PROTOCOL_INVALID_REQUEST));
    }
}

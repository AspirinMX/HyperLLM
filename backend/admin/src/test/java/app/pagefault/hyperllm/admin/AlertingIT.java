package app.pagefault.hyperllm.admin;

import app.pagefault.hyperllm.admin.alert.AlertDeliveryJob;
import app.pagefault.hyperllm.admin.alert.AlertDeliveryStore;
import app.pagefault.hyperllm.admin.alert.AlertSamplerJob;
import app.pagefault.hyperllm.admin.alert.NotificationException;
import app.pagefault.hyperllm.admin.alert.WebhookSender;
import app.pagefault.hyperllm.admin.config.AdminProperties;
import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.common.crypto.AesGcmCipher;
import app.pagefault.hyperllm.common.crypto.HmacSha256;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.domain.model.billing.BillingAlertRule;
import app.pagefault.hyperllm.domain.model.billing.NotificationEndpoint;
import app.pagefault.hyperllm.domain.model.enums.AlertType;
import app.pagefault.hyperllm.domain.model.enums.NotificationChannelType;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import app.pagefault.hyperllm.domain.model.enums.UserStatus;
import app.pagefault.hyperllm.domain.model.enums.ProjectEnvironment;
import app.pagefault.hyperllm.domain.model.enums.ProjectStatus;
import app.pagefault.hyperllm.domain.model.enums.ToggleStatus;
import app.pagefault.hyperllm.domain.model.auth.User;
import app.pagefault.hyperllm.domain.model.tenant.CustomerTier;
import app.pagefault.hyperllm.domain.model.tenant.Organization;
import app.pagefault.hyperllm.domain.model.tenant.Project;
import app.pagefault.hyperllm.domain.repository.BillingAlertRuleRepository;
import app.pagefault.hyperllm.domain.repository.CustomerTierRepository;
import app.pagefault.hyperllm.domain.repository.NotificationEndpointRepository;
import app.pagefault.hyperllm.domain.repository.OrganizationRepository;
import app.pagefault.hyperllm.domain.repository.ProjectBudgetRepository;
import app.pagefault.hyperllm.domain.repository.ProjectRepository;
import app.pagefault.hyperllm.domain.repository.UserRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises sampling, cooldown merging and signed delivery with a real local receiver.
 *
 * <p>The receiver is an HTTP server on loopback, which is only allowed because the test sets the
 * development switches; the production defaults reject both a plain HTTP scheme and a private
 * address, and that refusal is asserted too.
 */
@Tag("integration")
@SpringBootTest(
        classes = AdminApplication.class,
        properties = {
                "hyperllm.security.admin-jwt-secret=test-admin-jwt-secret-at-least-32-bytes",
                "hyperllm.security.aes-master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "hyperllm.security.key-pepper-v1=test-pepper-v1",
                "hyperllm.admin.mfa.required=false",
                "hyperllm.admin.bootstrap.email=",
                "hyperllm.admin.bootstrap.password=",
                "hyperllm.maintenance.enabled=false",
                "hyperllm.analytics.enabled=false",
                "spring.kafka.listener.auto-startup=false",
                "hyperllm.alerts.enabled=true",
                "hyperllm.alerts.allow-http=true",
                "hyperllm.alerts.allow-loopback=true",
                "hyperllm.alerts.sampler-delay=PT1H",
                "hyperllm.alerts.delivery-delay=PT1H"
        }
)
class AlertingIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hyperllm_alert_it")
            .withUsername("hyperllm")
            .withPassword("hyperllm");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private AlertSamplerJob sampler;
    @Autowired private AlertDeliveryJob deliveryJob;
    @Autowired private AlertDeliveryStore deliveries;
    @Autowired private WebhookSender webhookSender;
    @Autowired private AdminProperties properties;
    @Autowired private AesGcmCipher cipher;
    @Autowired private BillingAlertRuleRepository ruleRepository;
    @Autowired private NotificationEndpointRepository endpointRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CustomerTierRepository tierRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectBudgetRepository budgetRepository;
    @Autowired private StringRedisTemplate redis;

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastSignature = new AtomicReference<>();
    private final AtomicReference<String> lastTimestamp = new AtomicReference<>();
    private final AtomicReference<Integer> lastStatus = new AtomicReference<>(200);

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastSignature.set(exchange.getRequestHeaders().getFirst("X-HyperLLM-Signature"));
            lastTimestamp.set(exchange.getRequestHeaders().getFirst("X-HyperLLM-Timestamp"));
            exchange.sendResponseHeaders(lastStatus.get(), -1);
            exchange.close();
        });
        server.start();
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        properties.getAlerts().setAllowLoopback(true);
    }

    @Test
    void samplesAllThreeConditionsOncePerCooldownAndDeliversSignedWebhooks() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID orgId = seedOrg(now);
        String secret = "test-signing-secret";
        UUID endpointId = seedEndpoint(orgId, "http://127.0.0.1:" + port() + "/hook", secret, now);
        UUID softProject = seedProject(orgId, now, null, 100L);
        UUID hardProject = seedProject(orgId, now, 100L, null);
        seedRule(orgId, null, AlertType.LOW_BALANCE, 100L, endpointId, now);
        seedRule(orgId, softProject, AlertType.PROJECT_SOFT_CAP, 100L, endpointId, now);
        seedRule(orgId, hardProject, AlertType.PROJECT_HARD_CAP, 100L, endpointId, now);

        redis.opsForHash().putAll(GatewayRedisKeys.wallet(orgId), Map.of(
                "cash_micros", "50", "held_cash_micros", "0", "status", "ACTIVE", "loaded", "1"));
        budgetRepository.applyCharge(softProject, YearMonth.now(ZoneOffset.UTC).atDay(1), 150L);
        String month = YearMonth.now(ZoneOffset.UTC).toString();
        redis.opsForHash().putAll(GatewayRedisKeys.budgetSignal(hardProject, month), Map.of(
                "rejections", "3", "last_rejected_at", now.toString(),
                "hard_cap_micros", "100", "month", month));

        sampler.sampleOnce();
        var queued = deliveries.list("PENDING", 20);
        assertThat(queued).hasSize(3);
        assertThat(sampler.sampleOnce()).isEqualTo(3);
        assertThat(deliveries.list("PENDING", 20)).hasSize(3);

        assertThat(deliveryJob.deliverOnce()).isEqualTo(3);
        assertThat(deliveries.list("SENT", 20)).hasSize(3);
        assertThat(lastBody.get()).contains("\"alertType\"");
        String expected = "v1=" + HmacSha256.hmacHex(
                lastTimestamp.get() + "." + lastBody.get(), secret);
        assertThat(lastSignature.get()).isEqualTo(expected);
    }

    @Test
    void aPermanentFailureTerminatesAndCanBeRequeued() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID orgId = seedOrg(now);
        UUID endpointId = seedEndpoint(orgId, "http://127.0.0.1:" + port() + "/hook", "s", now);
        seedRule(orgId, null, AlertType.LOW_BALANCE, 1_000L, endpointId, now);
        redis.opsForHash().putAll(GatewayRedisKeys.wallet(orgId), Map.of(
                "cash_micros", "0", "held_cash_micros", "0", "status", "ACTIVE", "loaded", "1"));
        lastStatus.set(400);

        sampler.sampleOnce();
        deliveryJob.deliverOnce();

        var dead = deliveries.list("DEAD", 10);
        assertThat(dead).singleElement().satisfies(delivery -> {
            assertThat(delivery.attempts()).isEqualTo(1);
            assertThat(delivery.lastErrorClass()).isEqualTo("WEBHOOK_HTTP_400");
        });
        assertThat(deliveries.retry(dead.getFirst().id())).isTrue();
        assertThat(deliveries.list("PENDING", 10)).hasSize(1);
    }

    @Test
    void anInternalWebhookIsRefusedBeforeAnyRequestIsSent() {
        properties.getAlerts().setAllowLoopback(false);
        assertThatThrownBy(() -> webhookSender.send(
                "http://127.0.0.1:" + port() + "/hook", "id", "secret", "{}"))
                .isInstanceOfSatisfying(NotificationException.class, ex -> {
                    assertThat(ex.errorClass()).isEqualTo("WEBHOOK_PRIVATE_ADDRESS");
                    assertThat(ex.permanent()).isTrue();
                });
        assertThat(lastBody.get()).isNull();
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private UUID seedOrg(OffsetDateTime now) {
        CustomerTier tier = tierRepository.findActiveByCode("STANDARD").orElseThrow();
        UUID orgId = UuidV7.next();
        UUID ownerId = UuidV7.next();
        userRepository.insert(new User(ownerId, "alert-" + ownerId + "@acme.dev", now, "Alert Owner",
                null, "en-US", "USD", orgId, UserStatus.ACTIVE, now, null, 0L, now, now));
        organizationRepository.insert(new Organization(
                orgId, "Alert Org", "alert-" + orgId,
                ownerId, tier.id(), null, 50, false, OrgStatus.ACTIVE, null, 0L, now, now));
        return orgId;
    }

    private UUID seedProject(UUID orgId, OffsetDateTime now, Long hardCap, Long softCap) {
        UUID id = UuidV7.next();
        projectRepository.insert(new Project(id, orgId, "project-" + id, ProjectEnvironment.PRODUCTION,
                hardCap, softCap, null, null, null, null, false, (short) 7,
                null, ProjectStatus.ACTIVE, null, 0L, now, now));
        return id;
    }

    private UUID seedEndpoint(UUID orgId, String target, String secret, OffsetDateTime now) {
        UUID id = UuidV7.next();
        endpointRepository.insert(new NotificationEndpoint(id, orgId, NotificationChannelType.WEBHOOK,
                target, cipher.encryptUtf8(secret, id.toString()), ToggleStatus.ACTIVE, null, 0L, now, now));
        return id;
    }

    private void seedRule(UUID orgId, UUID projectId, AlertType type, long threshold,
                          UUID endpointId, OffsetDateTime now) {
        ruleRepository.insert(new BillingAlertRule(UuidV7.next(), orgId, projectId, type, threshold,
                List.of(endpointId), 60, null, ToggleStatus.ACTIVE, null, 0L, now, now));
    }
}

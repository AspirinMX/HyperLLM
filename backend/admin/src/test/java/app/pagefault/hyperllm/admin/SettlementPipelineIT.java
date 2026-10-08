package app.pagefault.hyperllm.admin;

import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.messaging.BillingSettlementEvent;
import app.pagefault.hyperllm.common.messaging.KafkaTopics;
import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.domain.model.auth.User;
import app.pagefault.hyperllm.domain.model.billing.Wallet;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import app.pagefault.hyperllm.domain.model.enums.UserStatus;
import app.pagefault.hyperllm.domain.model.tenant.CustomerTier;
import app.pagefault.hyperllm.domain.model.tenant.Organization;
import app.pagefault.hyperllm.domain.repository.CustomerTierRepository;
import app.pagefault.hyperllm.domain.repository.OrganizationRepository;
import app.pagefault.hyperllm.domain.repository.UserRepository;
import app.pagefault.hyperllm.domain.repository.WalletLedgerRepository;
import app.pagefault.hyperllm.domain.repository.WalletRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closes the Phase 1 cross-service gap: the gateway publishes settlement events and the admin
 * consumer persists them, but previously each half was only verified in its own JVM. This test
 * goes through the real Kafka listener (not a direct service call) into {@code wallet_ledger}.
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
                "spring.kafka.consumer.auto-offset-reset=earliest"
        }
)
class SettlementPipelineIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hyperllm_settlement_it")
            .withUsername("hyperllm")
            .withPassword("hyperllm");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.0") {
        @Override
        protected void configure() {
            super.configure();
            String listeners = getEnvMap().get("KAFKA_LISTENERS");
            if (listeners != null) {
                withEnv("KAFKA_LISTENERS", listeners.replace("0.0.0.0:", ":"));
            }
        }
    };

    static {
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired private ObjectMapper objectMapper;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private CustomerTierRepository tierRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private WalletLedgerRepository walletLedgerRepository;

    @Test
    void persistsSettlementEventThroughTheRealKafkaListenerExactlyOnce() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CustomerTier tier = tierRepository.findActiveByCode("STANDARD").orElseThrow();
        UUID ownerId = UuidV7.next();
        UUID orgId = UuidV7.next();

        userRepository.insert(new User(
                ownerId, "pipeline-" + ownerId + "@acme.dev", now, "Pipeline Owner",
                null, "en-US", "USD", orgId, UserStatus.ACTIVE, now, null, 0L, now, now
        ));
        organizationRepository.insert(new Organization(
                orgId, "Pipeline Org", "pipeline-" + orgId.toString().substring(0, 8),
                ownerId, tier.id(), null, 50, false, OrgStatus.ACTIVE, null, 0L, now, now
        ));
        walletRepository.insert(new Wallet(orgId, 1_000_000L, 0L, 1_000_000L, 0L, 0L, 0L, null, 0L, now));

        BillingSettlementEvent event = new BillingSettlementEvent(
                "req-pipeline-1", now, orgId, null, null,
                -250_000L, 0L, "req:req-pipeline-1", "Kafka pipeline integration"
        );
        String payload = objectMapper.writeValueAsString(event);

        kafkaTemplate.send(KafkaTopics.LLM_BILLING_SETTLEMENT, payload).get();

        assertThat(awaitLedgerRows(orgId, 1))
                .as("settlement event was not persisted through the Kafka listener")
                .isTrue();
        assertThat(walletRepository.findByOrgId(orgId).orElseThrow().cashBalanceMicros())
                .isEqualTo(750_000L);

        // Replaying the same event must not create a second ledger row nor deduct twice.
        kafkaTemplate.send(KafkaTopics.LLM_BILLING_SETTLEMENT, payload).get();
        Thread.sleep(2_000L);

        assertThat(walletLedgerRepository.listByOrg(orgId, PageQuery.of(1, 10)).total())
                .as("replayed settlement event was applied more than once")
                .isEqualTo(1L);
        assertThat(walletRepository.findByOrgId(orgId).orElseThrow().cashBalanceMicros())
                .isEqualTo(750_000L);
    }

    private boolean awaitLedgerRows(UUID orgId, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline) {
            if (walletLedgerRepository.listByOrg(orgId, PageQuery.of(1, 10)).total() >= expected) {
                return true;
            }
            Thread.sleep(200L);
        }
        return false;
    }
}

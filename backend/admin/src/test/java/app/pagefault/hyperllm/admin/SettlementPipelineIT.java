package app.pagefault.hyperllm.admin;

import app.pagefault.hyperllm.admin.consumer.LedgerFolder;
import app.pagefault.hyperllm.admin.maintenance.BatchExpiryJob;
import app.pagefault.hyperllm.admin.maintenance.OutboxRedeliveryJob;
import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.domain.model.enums.LedgerType;
import app.pagefault.hyperllm.domain.billing.WalletCreditOutbox;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import app.pagefault.hyperllm.domain.repository.AuditLogRepository;
import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
import app.pagefault.hyperllm.common.messaging.BillingMoneyEvent;
import app.pagefault.hyperllm.common.messaging.KafkaTopics;
import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.domain.model.auth.User;
import app.pagefault.hyperllm.domain.model.billing.Wallet;
import app.pagefault.hyperllm.domain.model.billing.BonusGrant;
import app.pagefault.hyperllm.domain.model.enums.EntitlementStatus;
import app.pagefault.hyperllm.domain.model.enums.GrantSource;
import app.pagefault.hyperllm.domain.repository.BonusGrantRepository;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
                "spring.kafka.consumer.auto-offset-reset=earliest",
                "hyperllm.maintenance.enabled=false"
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
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private AuditLogRepository auditRepository;
    @Autowired private OutboxRedeliveryJob reconciliation;
    @Autowired private LedgerFolder ledgerFolder;
    @Autowired private BonusGrantRepository grantRepository;
    @Autowired private BatchExpiryJob expiryJob;
    @Autowired private StringRedisTemplate redis;
    @Autowired private WalletCreditOutbox creditOutbox;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void persistsSettlementEventThroughTheRealKafkaListenerExactlyOnce() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID orgId = seedWallet(now);

        BillingMoneyEvent event = new BillingMoneyEvent(
                UuidV7.next(),
                BillingMoneyEvent.EventType.CONSUME,
                now,
                orgId,
                null,
                null,
                "req:req-pipeline-1",
                -250_000L,
                0L,
                0L,
                0L,
                java.util.List.of(BillingMoneyEvent.Leg.cash(250_000L)),
                "{\"v\":1}",
                null,
                false,
                "Kafka pipeline integration"
        );
        String payload = objectMapper.writeValueAsString(event);

        kafkaTemplate.send(KafkaTopics.CDC_BILLING, orgId.toString(), payload).get();

        assertThat(awaitLedgerRows(orgId, 1))
                .as("settlement event was not persisted through the Kafka listener")
                .isTrue();
        assertThat(walletRepository.findByOrgId(orgId).orElseThrow().cashBalanceMicros())
                .isEqualTo(750_000L);

        // Replaying the same event must not create a second ledger row nor deduct twice.
        kafkaTemplate.send(KafkaTopics.CDC_BILLING, orgId.toString(), payload).get();
        Thread.sleep(2_000L);

        assertThat(walletLedgerRepository.listByOrg(orgId, PageQuery.of(1, 10)).total())
                .as("replayed settlement event was applied more than once")
                .isEqualTo(1L);
        assertThat(walletRepository.findByOrgId(orgId).orElseThrow().cashBalanceMicros())
                .isEqualTo(750_000L);
    }

    @Test
    void foldsALateBonusDebitAfterExpiryWithoutExpiringTheSpentAmountTwice() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID orgId = seedWallet(now);
        UUID grantId = UuidV7.next();
        grantRepository.insert(new BonusGrant(grantId, orgId, GrantSource.SIGNUP, null, null, null,
                500L, 500L, now.minusSeconds(1), EntitlementStatus.ACTIVE, 0L, now.minusMinutes(1), now));
        assertThat(ledgerFolder.fold(new BillingMoneyEvent(UuidV7.next(), BillingMoneyEvent.EventType.BONUS_GRANT,
                now.minusMinutes(1), orgId, null, null, null, 0L, 500L, 0L, 0L, List.of(),
                null, null, false, null))).isTrue();
        var debit = new BillingMoneyEvent(UuidV7.next(), BillingMoneyEvent.EventType.CONSUME,
                now.minusSeconds(2), orgId, null, null, null, 0L, -150L, 0L, 0L,
                List.of(BillingMoneyEvent.Leg.bonus(grantId, 150L)), null, null, false, null);

        expiryJob.sweep();
        assertThat(walletRepository.findByOrgId(orgId).orElseThrow().bonusBalanceMicros()).isZero();
        assertThat(ledgerFolder.fold(debit)).isTrue();
        assertThat(ledgerFolder.fold(debit)).isFalse();

        var wallet = walletRepository.findByOrgId(orgId).orElseThrow();
        assertThat(wallet.bonusBalanceMicros()).isZero();
        assertThat(wallet.totalConsumedBonusMicros()).isEqualTo(150L);
        assertThat(grantRepository.findById(grantId).orElseThrow().remainingMicros()).isEqualTo(350L);
        assertThat(walletLedgerRepository.listByOrg(orgId, PageQuery.of(1, 10)).items())
                .extracting(row -> row.bonusDeltaMicros()).containsExactlyInAnyOrder(500L, -500L, -150L, 150L);
    }

    @Test
    void replaysOutboxCopyAndRepairsTheExistingRowThroughKafka() throws Exception {
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2);
        UUID orgId = seedWallet(at);
        var event = recordPendingDebit(orgId, at);

        reconciliation.advance(at.plusMinutes(2));
        assertThat(outboxRepository.exists(event.eventId())).isTrue();
        assertThat(redis.opsForZSet().score(GatewayRedisKeys.settlePending(), event.eventId().toString())).isNotNull();
        assertThat(walletLedgerRepository.listByOrg(orgId, PageQuery.of(1, 10)).total()).isZero();

        reconciliation.advance(at.plusMinutes(5));
        assertThat(awaitLedgerRows(orgId, 1)).isTrue();
        assertThat(ledgerFolder.fold(event)).isFalse();
        assertThat(walletRepository.findByOrgId(orgId).orElseThrow().cashBalanceMicros()).isEqualTo(750_000L);
    }

    @Test
    void replaysTheRedisCopyWhenOnlyTheWatermarkRemains() throws Exception {
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(6);
        UUID orgId = seedWallet(at);
        var event = recordPendingDebit(orgId, at);
        outboxRepository.advanceWatermark(UuidV7.next());
        assertThat(outboxRepository.exists(event.eventId())).isFalse();

        reconciliation.advance(at.plusMinutes(6));

        assertThat(awaitLedgerRows(orgId, 1)).isTrue();
        assertThat(ledgerFolder.fold(event)).isFalse();
        assertThat(walletLedgerRepository.listByOrg(orgId, PageQuery.of(1, 10)).items())
                .singleElement().satisfies(row -> assertThat(row.ledgerType()).isEqualTo(LedgerType.CONSUME));
        assertThat(walletRepository.findByOrgId(orgId).orElseThrow().cashBalanceMicros()).isEqualTo(750_000L);
    }

    @Test
    void repairsAProvenDebitAndFencesLateDelivery() throws Exception {
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(17);
        UUID orgId = seedWallet(at);
        var event = recordPendingDebit(orgId, at);

        reconciliation.advance(at.plusMinutes(17));
        assertThat(outboxRepository.exists(event.eventId())).isFalse();
        assertThat(walletLedgerRepository.listByOrg(orgId, PageQuery.of(1, 10)).items())
                .singleElement().satisfies(row -> {
                    assertThat(row.id()).isEqualTo(event.eventId());
                    assertThat(row.ledgerType()).isEqualTo(LedgerType.ADJUST);
                    assertThat(row.cashDeltaMicros()).isEqualTo(-250_000L);
                });
        assertThat(auditRepository.listByOrg(orgId, PageQuery.of(1, 10)).items())
                .singleElement().satisfies(row -> assertThat(row.action()).isEqualTo("billing.reconcile.adjust"));
        assertThat(redis.opsForZSet().score(GatewayRedisKeys.settlePending(), event.eventId().toString())).isNull();
        assertThat(ledgerFolder.fold(event)).isFalse();
        assertThat(walletRepository.findByOrgId(orgId).orElseThrow().cashBalanceMicros()).isEqualTo(750_000L);
        assertThat(redis.hasKey(GatewayRedisKeys.settleWaiting(orgId))).isFalse();
        assertThat(redis.opsForHash().get(GatewayRedisKeys.ledgerPosition(orgId), "debit_version")).isEqualTo("1");
    }

    @Test
    void writesOffWithoutDebitingWhenTheMirrorWasRecreatedAndTheCopyExpired() throws Exception {
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(17);
        UUID orgId = seedWallet(at);
        var event = recordPendingDebit(orgId, at);
        redis.opsForHash().put(GatewayRedisKeys.wallet(orgId), "ledger_generation", "replacement");

        reconciliation.advance(at.plusMinutes(17));
        assertThat(walletLedgerRepository.listByOrg(orgId, PageQuery.of(1, 10)).total()).isZero();
        redis.delete(GatewayRedisKeys.settleEvent(event.eventId().toString()));

        reconciliation.advance(at.plusMinutes(31));
        assertThat(auditRepository.listByOrg(orgId, PageQuery.of(1, 10)).items())
                .singleElement().satisfies(row -> assertThat(row.action()).isEqualTo("billing.reconcile.write_off"));
        assertThat(redis.opsForZSet().score(GatewayRedisKeys.settlePending(), event.eventId().toString())).isNull();
        assertThat(ledgerFolder.fold(event)).isFalse();
        assertThat(walletRepository.findByOrgId(orgId).orElseThrow().cashBalanceMicros()).isEqualTo(1_000_000L);
    }

    @Test
    void retainsAnUnconfirmedBonusAuthorizationAndProjectsItOnceOnReplay() throws Exception {
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(6);
        UUID orgId = seedWallet(at);
        String wallet = GatewayRedisKeys.wallet(orgId);
        String bonus = GatewayRedisKeys.bonus(orgId);
        redis.opsForHash().put(wallet, "cash_micros", "750000");
        redis.opsForZSet().add(bonus, "__loaded_v2__", 0);
        var event = new TransactionTemplate(transactionManager).execute(status -> creditOutbox.append(
                orgId, "signup-bonus:" + orgId, 0L, 500L, BillingMoneyEvent.CreditSource.SIGNUP, null, at));
        UUID eventId = event.eventId();
        outboxRepository.deleteUpTo(UuidV7.next(), 100);
        outboxRepository.deleteOlderThan(at.plusSeconds(1));
        assertThat(outboxRepository.exists(eventId)).isTrue();
        assertThat(walletRepository.findByOrgId(orgId).orElseThrow().bonusBalanceMicros()).isZero();

        reconciliation.redeliver();
        assertThat(awaitLedgerRows(orgId, 1)).isTrue();
        assertThat(ledgerFolder.fold(event)).isFalse();
        assertThat(walletRepository.findByOrgId(orgId).orElseThrow().bonusBalanceMicros()).isEqualTo(500L);
        assertThat(redis.opsForHash().get(GatewayRedisKeys.bonusItem(eventId), "remaining_micros")).isEqualTo("500");
        assertThat(redis.opsForHash().get(wallet, "cash_micros")).isEqualTo("750000");
        assertThat(grantRepository.findById(eventId).orElseThrow().grantSource()).isEqualTo(GrantSource.SIGNUP);
        outboxRepository.deleteUpTo(UuidV7.next(), 100);
        assertThat(outboxRepository.exists(eventId)).isFalse();
    }

    private BillingMoneyEvent recordPendingDebit(UUID orgId, OffsetDateTime at) throws Exception {
        UUID id = UuidV7.next();
        var event = new BillingMoneyEvent(id, BillingMoneyEvent.EventType.CONSUME, at, orgId, null, null,
                "req:" + id, -250_000L, 0, 0, 0, List.of(BillingMoneyEvent.Leg.cash(250_000)),
                "{}", id, false, "Reconciliation integration");
        String key = id.toString();
        redis.opsForValue().set(GatewayRedisKeys.settleEvent(key), objectMapper.writeValueAsString(event));
        redis.opsForHash().put(GatewayRedisKeys.settlePendingOwners(), key, orgId + "|" + at);
        var wallet = GatewayRedisKeys.wallet(orgId);
        redis.opsForHash().putAll(wallet, Map.of("cash_micros", "750000", "ledger_generation", key));
        redis.opsForValue().set(GatewayRedisKeys.settleProof(key), objectMapper.writeValueAsString(Map.of(wallet, key)));
        redis.opsForZSet().add(GatewayRedisKeys.settlePending(), key, at.toInstant().toEpochMilli());
        redis.opsForSet().add(GatewayRedisKeys.settleWaiting(orgId), key);
        return event;
    }

    private UUID seedWallet(OffsetDateTime now) {
        CustomerTier tier = tierRepository.findActiveByCode("STANDARD").orElseThrow();
        UUID ownerId = UuidV7.next();
        UUID orgId = UuidV7.next();

        userRepository.insert(new User(
                ownerId, "pipeline-" + ownerId + "@acme.dev", now, "Pipeline Owner",
                null, "en-US", "USD", orgId, UserStatus.ACTIVE, now, null, 0L, now, now
        ));
        organizationRepository.insert(new Organization(
                orgId, "Pipeline Org", "pipeline-" + orgId,
                ownerId, tier.id(), null, 50, false, OrgStatus.ACTIVE, null, 0L, now, now
        ));
        walletRepository.insert(new Wallet(orgId, 1_000_000L, 0L, 1_000_000L, 0L, 0L, 0L, null, 0L, now));
        return orgId;
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

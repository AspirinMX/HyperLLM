package app.pagefault.hyperllm.admin;

import app.pagefault.hyperllm.admin.reconcile.ReconciliationJob;
import app.pagefault.hyperllm.admin.reconcile.ReconciliationService;
import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.domain.model.auth.User;
import app.pagefault.hyperllm.domain.model.billing.BonusGrant;
import app.pagefault.hyperllm.domain.model.billing.Wallet;
import app.pagefault.hyperllm.domain.model.billing.WalletLedgerEntry;
import app.pagefault.hyperllm.domain.model.enums.BudgetMonthState;
import app.pagefault.hyperllm.domain.model.enums.EntitlementStatus;
import app.pagefault.hyperllm.domain.model.enums.GrantSource;
import app.pagefault.hyperllm.domain.model.enums.LedgerType;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import app.pagefault.hyperllm.domain.model.enums.ProjectEnvironment;
import app.pagefault.hyperllm.domain.model.enums.ProjectStatus;
import app.pagefault.hyperllm.domain.model.enums.UserStatus;
import app.pagefault.hyperllm.domain.model.tenant.CustomerTier;
import app.pagefault.hyperllm.domain.model.tenant.Organization;
import app.pagefault.hyperllm.domain.model.tenant.Project;
import app.pagefault.hyperllm.domain.repository.BonusGrantRepository;
import app.pagefault.hyperllm.domain.repository.CustomerTierRepository;
import app.pagefault.hyperllm.domain.repository.OrganizationRepository;
import app.pagefault.hyperllm.domain.repository.ProjectBudgetRepository;
import app.pagefault.hyperllm.domain.repository.ProjectRepository;
import app.pagefault.hyperllm.domain.repository.UserRepository;
import app.pagefault.hyperllm.domain.repository.WalletLedgerRepository;
import app.pagefault.hyperllm.domain.repository.WalletRepository;
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

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconciliation compares each pair of records and records only what it cannot explain.
 *
 * <p>A settled-but-unfolded window is attributed to the pending settlement instead of raising a
 * balance alarm, and a mismatch that no evidence explains becomes an open finding with its numbers.
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
                "hyperllm.alerts.enabled=false",
                "spring.kafka.listener.auto-startup=false",
                "hyperllm.reconciliation.delay=PT24H"
        }
)
class ReconciliationIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hyperllm_reconcile_it")
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

    @Autowired private ReconciliationJob job;
    @Autowired private ReconciliationService service;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CustomerTierRepository tierRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private WalletLedgerRepository ledgerRepository;
    @Autowired private BonusGrantRepository grantRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectBudgetRepository budgetRepository;
    @Autowired private StringRedisTemplate redis;

    @Test
    void recordsAnUnexplainedWalletDifferenceAndCanBeResolved() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID orgId = seedOrg(now);
        walletRepository.insert(new Wallet(orgId, 1_000L, 0L, 1_000L, 0L, 0L, 0L, null, 0L, now));
        ledgerRepository.insertIdempotent(new WalletLedgerEntry(UuidV7.next(), orgId, null, null,
                LedgerType.RECHARGE, "recharge:" + orgId, 900L, 0L, 0L, null, null, null,
                null, null, null, "seed", now, null));

        job.reconcileOnce(500);

        var finding = openFindings(orgId, "LEDGER_VS_WALLET");
        assertThat(finding).isNotNull();
        assertThat(finding.severity()).isEqualTo("P1");
        assertThat(finding.deltaMicros()).isEqualTo(100L);

        service.resolve(finding.id(), "known seed difference");
        assertThat(openFindings(orgId, "LEDGER_VS_WALLET")).isNull();
    }

    @Test
    void attributesAnUnfoldedSettlementWindowInsteadOfRaisingABalanceAlarm() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID orgId = seedOrg(now);
        walletRepository.insert(new Wallet(orgId, 1_000L, 0L, 1_000L, 0L, 0L, 0L, null, 0L, now));
        OffsetDateTime at = now.minusMinutes(30);
        String eventId = UuidV7.next().toString();
        redis.opsForValue().set(GatewayRedisKeys.settleEvent(eventId), "{}");
        redis.opsForHash().put(GatewayRedisKeys.settlePendingOwners(), eventId, orgId + "|" + at);
        redis.opsForZSet().add(GatewayRedisKeys.settlePending(), eventId, at.toInstant().toEpochMilli());

        job.reconcileOnce(500);

        assertThat(openFindings(orgId, "PENDING_SETTLEMENT")).isNotNull();
        assertThat(openFindings(orgId, "LEDGER_VS_WALLET")).isNull();
    }

    @Test
    void comparesBatchesAndLiveRedisWithTheWallet() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID orgId = seedOrg(now);
        walletRepository.insert(new Wallet(orgId, 1_000L, 500L, 1_000L, 500L, 0L, 0L, null, 0L, now));
        grantRepository.insert(new BonusGrant(UuidV7.next(), orgId, GrantSource.ADMIN_GRANT,
                null, null, null, 500L, 400L, null, EntitlementStatus.ACTIVE, 0L, now, now));
        redis.opsForHash().putAll(GatewayRedisKeys.wallet(orgId), Map.of(
                "cash_micros", "900", "held_cash_micros", "0", "status", "ACTIVE", "loaded", "1"));

        job.reconcileOnce(500);

        assertThat(openFindings(orgId, "BATCHES_VS_WALLET")).isNotNull();
        assertThat(openFindings(orgId, "WALLET_VS_REDIS")).isNotNull();
    }

    @Test
    void resolvesAnUnverifiedBudgetMonthAndDropsTheLiveAuthority() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID orgId = seedOrg(now);
        UUID projectId = UuidV7.next();
        projectRepository.insert(new Project(projectId, orgId, "budget-" + projectId,
                ProjectEnvironment.PRODUCTION, 10_000L, 5_000L, null, null, null, null,
                false, (short) 7, null, ProjectStatus.ACTIVE, null, 0L, now, now));
        YearMonth month = YearMonth.now(ZoneOffset.UTC);
        budgetRepository.markUnverified(projectId, month.atDay(1));
        String key = GatewayRedisKeys.projectBudget(projectId, month.toString());
        redis.opsForHash().putAll(key, Map.of("state", "UNVERIFIED", "used_micros", "0"));

        service.resolveBudgetMonth(projectId, month.atDay(1), 1_234L);

        assertThat(budgetRepository.find(projectId, month.atDay(1)).orElseThrow().state())
                .isEqualTo(BudgetMonthState.CONFIRMED);
        assertThat(budgetRepository.confirmedMicros(projectId, month.atDay(1))).isEqualTo(1_234L);
        assertThat(redis.hasKey(key)).isFalse();
    }

    private ReconciliationService.Finding openFindings(UUID orgId, String kind) {
        return service.list("OPEN", 200).stream()
                .filter(finding -> finding.orgId().equals(orgId) && finding.checkKind().equals(kind))
                .findFirst().orElse(null);
    }

    private UUID seedOrg(OffsetDateTime now) {
        CustomerTier tier = tierRepository.findActiveByCode("STANDARD").orElseThrow();
        UUID orgId = UuidV7.next();
        UUID ownerId = UuidV7.next();
        userRepository.insert(new User(ownerId, "reconcile-" + ownerId + "@acme.dev", now, "Owner",
                null, "en-US", "USD", orgId, UserStatus.ACTIVE, now, null, 0L, now, now));
        organizationRepository.insert(new Organization(orgId, "Reconcile Org", "reconcile-" + orgId,
                ownerId, tier.id(), null, 50, false, OrgStatus.ACTIVE, null, 0L, now, now));
        return orgId;
    }
}

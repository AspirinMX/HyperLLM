package app.pagefault.hyperllm.domain;

import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.domain.model.auth.User;
import app.pagefault.hyperllm.domain.model.billing.AuditLogEntry;
import app.pagefault.hyperllm.domain.model.billing.Wallet;
import app.pagefault.hyperllm.domain.model.billing.WalletLedgerEntry;
import app.pagefault.hyperllm.domain.model.enums.ActorType;
import app.pagefault.hyperllm.domain.model.enums.BillingMode;
import app.pagefault.hyperllm.domain.model.enums.BindingStatus;
import app.pagefault.hyperllm.domain.model.enums.ChannelStatus;
import app.pagefault.hyperllm.domain.model.enums.LedgerType;
import app.pagefault.hyperllm.domain.model.enums.ModelCapability;
import app.pagefault.hyperllm.domain.model.enums.ModelStatus;
import app.pagefault.hyperllm.domain.model.enums.ModelVisibility;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import app.pagefault.hyperllm.domain.model.enums.ProtocolFamily;
import app.pagefault.hyperllm.domain.model.enums.RoutingStrategy;
import app.pagefault.hyperllm.domain.model.enums.UserStatus;
import app.pagefault.hyperllm.domain.model.routing.Channel;
import app.pagefault.hyperllm.domain.model.routing.GatewayModelRoute;
import app.pagefault.hyperllm.domain.model.routing.ModelChannelBinding;
import app.pagefault.hyperllm.domain.model.routing.PricingRule;
import app.pagefault.hyperllm.domain.model.routing.Provider;
import app.pagefault.hyperllm.domain.model.routing.VirtualModel;
import app.pagefault.hyperllm.domain.model.tenant.CustomerTier;
import app.pagefault.hyperllm.domain.model.tenant.Organization;
import app.pagefault.hyperllm.domain.repository.AuditLogRepository;
import app.pagefault.hyperllm.domain.repository.ChannelRepository;
import app.pagefault.hyperllm.domain.repository.CustomerTierRepository;
import app.pagefault.hyperllm.domain.repository.GatewayRouteRepository;
import app.pagefault.hyperllm.domain.repository.ModelChannelBindingRepository;
import app.pagefault.hyperllm.domain.repository.OrganizationRepository;
import app.pagefault.hyperllm.domain.repository.PricingRuleRepository;
import app.pagefault.hyperllm.domain.repository.ProviderRepository;
import app.pagefault.hyperllm.domain.repository.UserRepository;
import app.pagefault.hyperllm.domain.repository.VirtualModelRepository;
import app.pagefault.hyperllm.domain.repository.WalletLedgerRepository;
import app.pagefault.hyperllm.domain.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingAndBillingRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private ProviderRepository providerRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private VirtualModelRepository virtualModelRepository;
    @Autowired private ModelChannelBindingRepository bindingRepository;
    @Autowired private PricingRuleRepository pricingRuleRepository;
    @Autowired private GatewayRouteRepository gatewayRouteRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CustomerTierRepository tierRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private WalletLedgerRepository walletLedgerRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    @Test
    void hydratesRouteWithPriorityTierCostAndEffectivePricingWindow() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Provider openai = providerRepository.findByCode("OPENAI").orElseThrow();
        Provider deepseek = providerRepository.findByCode("DEEPSEEK").orElseThrow();

        UUID primaryChannelId = UuidV7.next();
        UUID backupChannelId = UuidV7.next();
        channelRepository.insert(new Channel(
                primaryChannelId, openai.id(), "openai-main", "us-east", null,
                "cipher-1".getBytes(StandardCharsets.UTF_8), (short) 1, 9000,
                1000, 100_000, 50, "gpt-4o-mini", ChannelStatus.ACTIVE, null, 0L, now, now
        ));
        channelRepository.insert(new Channel(
                backupChannelId, deepseek.id(), "deepseek-backup", "ap-east", "https://custom.ds.internal/v1",
                "cipher-2".getBytes(StandardCharsets.UTF_8), (short) 1, 7000,
                null, null, null, null, ChannelStatus.ACTIVE, null, 0L, now, now
        ));

        UUID modelId = UuidV7.next();
        String modelCode = "gpt-5-" + modelId.toString().substring(0, 6);
        virtualModelRepository.insert(new VirtualModel(
                modelId, modelCode, "GPT-5", "OpenAI", ModelCapability.CHAT, ProtocolFamily.OPENAI,
                128_000, 16_384, true, true, false, true, RoutingStrategy.PRIORITY_WEIGHTED,
                false, 300, ModelVisibility.PUBLIC, null, ModelStatus.ACTIVE, "{}", null, 0L, now, now
        ));

        bindingRepository.insert(new ModelChannelBinding(
                UuidV7.next(), modelId, backupChannelId, "deepseek-chat", (short) 2, 50,
                null, null, "{}", BindingStatus.ACTIVE, null, 0L, now, now
        ));
        bindingRepository.insert(new ModelChannelBinding(
                UuidV7.next(), modelId, primaryChannelId, "gpt-5", (short) 1, 100,
                8500, null, "{}", BindingStatus.ACTIVE, null, 0L, now, now
        ));

        OffsetDateTime t1 = now.minusDays(2);
        OffsetDateTime t2 = now.minusHours(1);
        pricingRuleRepository.insert(new PricingRule(
                UuidV7.next(), modelId, BillingMode.TOKEN,
                2_000_000L, 8_000_000L, 500_000L, 0L, 0L, 0L,
                "[]", "{}", t1, t2, null, now
        ));
        // Back-to-back window [t2, null) must NOT overlap [t1, t2)
        assertThat(pricingRuleRepository.hasOverlappingWindow(modelId, t2, null)).isFalse();
        pricingRuleRepository.insert(new PricingRule(
                UuidV7.next(), modelId, BillingMode.TOKEN,
                1_500_000L, 6_000_000L, 375_000L, 0L, 0L, 0L,
                "[]", "{}", t2, null, null, now
        ));
        assertThat(pricingRuleRepository.hasOverlappingWindow(modelId, now, null)).isTrue();

        GatewayModelRoute route = gatewayRouteRepository.findActiveRouteByModelCode(modelCode, now).orElseThrow();
        assertThat(route.activePricing().inputPriceMicros1m()).isEqualTo(1_500_000L);
        assertThat(route.candidates()).hasSize(2);
        assertThat(route.candidates().get(0).channelId()).isEqualTo(primaryChannelId);
        assertThat(route.candidates().get(0).resolvedBaseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(route.candidates().get(0).effectiveCostDiscountBps()).isEqualTo(8500);
        assertThat(route.candidates().get(1).resolvedBaseUrl()).isEqualTo("https://custom.ds.internal/v1");
        assertThat(route.candidates().get(1).effectiveCostDiscountBps()).isEqualTo(7000);
    }

    @Test
    void enforcesWalletCheckConstraintsIdempotentPartitionedLedgerAndAuditLogs() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID userId = UuidV7.next();
        UUID orgId = UuidV7.next();
        CustomerTier standard = tierRepository.findActiveByCode("STANDARD").orElseThrow();

        userRepository.insert(new User(
                userId, "bill-" + userId + "@acme.dev", now, "Bill", null,
                "en-US", "USD", null, UserStatus.ACTIVE, null, null, 0L, now, now
        ));
        organizationRepository.insert(new Organization(
                orgId, "Billing Org", "bill-" + orgId.toString().substring(0, 8),
                userId, standard.id(), null, 50, true, OrgStatus.ACTIVE, null, 0L, now, now
        ));

        walletRepository.insert(new Wallet(orgId, 0L, 1_000_000L, 0L, 1_000_000L, 0L, 0L, 100_000L, 0L, now));
        assertThat(walletRepository.applyDelta(orgId, 5_000_000L, 0L, 5_000_000L, 0L, 0L, 0L, now.plusSeconds(1))).isTrue();
        // Attempting to overdraw cash must fail cleanly without going negative
        assertThat(walletRepository.applyDelta(orgId, -10_000_000L, 0L, 0L, 0L, 0L, 0L, now.plusSeconds(2))).isFalse();
        assertThat(walletRepository.applyDelta(orgId, -1_500_000L, -500_000L, 0L, 0L, 1_500_000L, 500_000L, now.plusSeconds(3))).isTrue();

        Wallet wallet = walletRepository.findByOrgId(orgId).orElseThrow();
        assertThat(wallet.cashBalanceMicros()).isEqualTo(3_500_000L);
        assertThat(wallet.bonusBalanceMicros()).isEqualTo(500_000L);

        String reqId = "req-" + UuidV7.next();
        WalletLedgerEntry entry = new WalletLedgerEntry(
                UuidV7.next(), orgId, null, null, LedgerType.CONSUME, "req:" + reqId,
                -1_500_000L, -500_000L, 0L, 3_500_000L, 500_000L, null,
                reqId, null, null, "Chat completion settlement", now
        );
        assertThat(walletLedgerRepository.insertIdempotent(entry)).isTrue();
        // Replaying exact same idempotency_key + created_at must no-op
        assertThat(walletLedgerRepository.insertIdempotent(entry)).isFalse();
        assertThat(walletLedgerRepository.listByOrg(orgId, PageQuery.of(1, 20)).total()).isEqualTo(1L);

        auditLogRepository.insert(new AuditLogEntry(
                UuidV7.next(), ActorType.USER, userId, orgId, "ORG.CREATE",
                "organizations", orgId.toString(), "{\"name\":\"Billing Org\"}", "198.51.100.2", "trace-1", now
        ));
        assertThat(auditLogRepository.listByOrg(orgId, PageQuery.of(1, 10)).items()).hasSize(1);
    }
}

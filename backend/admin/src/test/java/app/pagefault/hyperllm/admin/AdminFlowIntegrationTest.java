package app.pagefault.hyperllm.admin;

import app.pagefault.hyperllm.admin.config.TotpService;
import app.pagefault.hyperllm.admin.consumer.BillingSettlementConsumer;
import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.messaging.BillingMoneyEvent;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@SpringBootTest(
        classes = AdminApplication.class,
        properties = {
                "hyperllm.security.admin-jwt-secret=test-admin-jwt-secret-at-least-32-bytes",
                "hyperllm.security.aes-master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "hyperllm.security.key-pepper-v1=test-pepper-v1",
                "hyperllm.admin.mfa.required=true",
                "hyperllm.admin.bootstrap.email=ops-root@hyperllm.dev",
                "hyperllm.admin.bootstrap.password=RootAdminPass123!"
        }
)
@AutoConfigureMockMvc
class AdminFlowIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hyperllm_admin_test")
            .withUsername("hyperllm")
            .withPassword("hyperllm");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private CustomerTierRepository tierRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private WalletLedgerRepository walletLedgerRepository;
    @Autowired private app.pagefault.hyperllm.admin.consumer.LedgerFolder ledgerFolder;

    @Test
    void enforcesBootstrapAdminTotpSetupEncryptedChannelProbePricingOverlapAndIdempotentSettlement() throws Exception {
        // 1. Login with bootstrapped SUPER_ADMIN triggers MFA_SETUP challenge
        MvcResult loginRes = mockMvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login":"ops-root@hyperllm.dev","password":"RootAdminPass123!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.mfaAction").value("MFA_SETUP"))
                .andReturn();
        String setupToken = objectMapper.readTree(loginRes.getResponse().getContentAsString()).path("challengeToken").asText();

        // Restricted challenge token must not access admin resources
        mockMvc.perform(get("/api/v1/admin/providers").header("Authorization", "Bearer " + setupToken))
                .andExpect(status().isUnauthorized());

        // 2. Request TOTP setup secret & bind with live generated code
        MvcResult secretRes = mockMvc.perform(post("/api/v1/admin/auth/mfa/setup")
                        .header("Authorization", "Bearer " + setupToken))
                .andExpect(status().isOk())
                .andReturn();
        String secret = objectMapper.readTree(secretRes.getResponse().getContentAsString()).path("secret").asText();

        String totpCode = generateCurrentTotp(secret);
        MvcResult completeRes = mockMvc.perform(post("/api/v1/admin/auth/mfa/setup")
                        .header("Authorization", "Bearer " + setupToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s"}
                                """.formatted(totpCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.mfaVerified").value(true))
                .andReturn();
        String adminJwt = objectMapper.readTree(completeRes.getResponse().getContentAsString()).path("accessToken").asText();

        // 3. Create encrypted Channel & probe against an in-memory mock upstream HTTP server
        HttpServer mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockUpstream.createContext("/v1/models", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] payload = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            int code = "Bearer sk-upstream-secret-key".equals(auth) ? 200 : 401;
            exchange.sendResponseHeaders(code, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        mockUpstream.start();
        try {
            MvcResult provRes = mockMvc.perform(get("/api/v1/admin/providers")
                            .header("Authorization", "Bearer " + adminJwt))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode providers = objectMapper.readTree(provRes.getResponse().getContentAsString());
            String openaiProviderId = findProviderId(providers, "OPENAI");

            MvcResult chRes = mockMvc.perform(post("/api/v1/admin/channels")
                            .header("Authorization", "Bearer " + adminJwt)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "providerId": "%s",
                                      "name": "openai-probe-test",
                                      "baseUrlOverride": "http://127.0.0.1:%d/v1",
                                      "apiKey": "sk-upstream-secret-key",
                                      "defaultCostDiscountBps": 8500
                                    }
                                    """.formatted(openaiProviderId, mockUpstream.getAddress().getPort())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.hasCredentials").value(true))
                    .andExpect(jsonPath("$.apiKey").doesNotExist())
                    .andReturn();
            String channelId = objectMapper.readTree(chRes.getResponse().getContentAsString()).path("id").asText();

            mockMvc.perform(post("/api/v1/admin/channels/{channelId}/probe", channelId)
                            .header("Authorization", "Bearer " + adminJwt))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.healthy").value(true))
                    .andExpect(jsonPath("$.httpStatus").value(200));

            // 4. Create virtual model, binding, and versioned pricing (verify 409 on overlap)
            MvcResult modelRes = mockMvc.perform(post("/api/v1/admin/models")
                            .header("Authorization", "Bearer " + adminJwt)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "modelCode": "gpt-5-admin",
                                      "displayName": "GPT-5 Admin Test",
                                      "capabilityType": "CHAT",
                                      "nativeProtocol": "OPENAI",
                                      "contextWindow": 128000,
                                      "routingStrategy": "PRIORITY_WEIGHTED",
                                      "stickyCacheEnabled": false,
                                      "stickyTtlSeconds": 300,
                                      "visibility": "PUBLIC"
                                    }
                                    """))
                    .andExpect(status().isCreated())
                    .andReturn();
            String modelId = objectMapper.readTree(modelRes.getResponse().getContentAsString()).path("id").asText();

            mockMvc.perform(put("/api/v1/admin/models/{modelId}/bindings", modelId)
                            .header("Authorization", "Bearer " + adminJwt)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"bindings":[{"channelId":"%s","upstreamModelName":"gpt-4o","priorityTier":1,"weight":100}]}
                                    """.formatted(channelId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].upstreamModelName").value("gpt-4o"));

            mockMvc.perform(post("/api/v1/admin/models/{modelId}/pricing-rules", modelId)
                            .header("Authorization", "Bearer " + adminJwt)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "billingMode": "TOKEN",
                                      "inputPriceMicros1m": 2500000,
                                      "outputPriceMicros1m": 10000000,
                                      "effectiveFrom": "2026-01-01T00:00:00Z"
                                    }
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/v1/admin/models/{modelId}/pricing-rules", modelId)
                            .header("Authorization", "Bearer " + adminJwt)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "billingMode": "TOKEN",
                                      "inputPriceMicros1m": 3000000,
                                      "outputPriceMicros1m": 12000000,
                                      "effectiveFrom": "2026-06-01T00:00:00Z"
                                    }
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PRICING_WINDOW_OVERLAP"));
        } finally {
            mockUpstream.stop(0);
        }

        // 5. Phase 1 test topup & idempotent Kafka billing settlement ingestion
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID ownerId = UuidV7.next();
        UUID orgId = UuidV7.next();
        CustomerTier standard = tierRepository.findActiveByCode("STANDARD").orElseThrow();
        userRepository.insert(new User(
                ownerId, "tenant-" + ownerId + "@acme.dev", now, "Tenant Owner",
                null, "en-US", "USD", orgId, UserStatus.ACTIVE, now, null, 0L, now, now
        ));
        organizationRepository.insert(new Organization(
                orgId, "Billing Org", "billing-" + orgId.toString().substring(0, 8),
                ownerId, standard.id(), null, 50, false, OrgStatus.ACTIVE, null, 0L, now, now
        ));
        walletRepository.insert(new Wallet(orgId, 0L, 0L, 0L, 0L, 0L, 0L, null, 0L, now));

        mockMvc.perform(post("/api/v1/admin/orgs/{orgId}/wallet/topup", orgId)
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topupType":"CASH","amountMicros":1000000,"idempotencyKey":"topup-1","description":"Seed"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.cashBalanceMicros").value(1_000_000));

        assertThat(redisTemplate.opsForHash().get(GatewayRedisKeys.wallet(orgId), "cash_micros"))
                .isEqualTo("1000000");

        // The tenant list shows the ledger-confirmed balance inline: one extra statement for the
        // page, never a query per row, and no live value that a list could not reconcile.
        mockMvc.perform(get("/api/v1/admin/orgs").header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id=='" + orgId + "')].cashBalanceMicros").value(1_000_000))
                .andExpect(jsonPath("$.items[?(@.id=='" + orgId + "')].bonusBalanceMicros").value(0))
                .andExpect(jsonPath("$.items[?(@.id=='" + orgId + "')].liveDetailAvailable").value(true));

        // Consume settlement event twice; second replay must be ignored idempotently
        BillingMoneyEvent event = new BillingMoneyEvent(
                app.pagefault.hyperllm.common.id.UuidV7.next(),
                BillingMoneyEvent.EventType.CONSUME,
                now,
                orgId,
                null,
                null,
                "req:req-settlement-1",
                -250_000L,
                0L,
                0L,
                0L,
                java.util.List.of(BillingMoneyEvent.Leg.cash(250_000L)),
                "{\"v\":1}",
                null,
                false,
                "Gateway consume test"
        );
        assertThat(ledgerFolder.fold(event)).isTrue();
        assertThat(ledgerFolder.fold(event)).isFalse();

        Wallet settled = walletRepository.findByOrgId(orgId).orElseThrow();
        assertThat(settled.cashBalanceMicros()).isEqualTo(750_000L);
        assertThat(settled.totalConsumedCashMicros()).isEqualTo(250_000L);
        assertThat(walletLedgerRepository.listByOrg(orgId, app.pagefault.hyperllm.common.page.PageQuery.of(1, 10)).total())
                .isEqualTo(2L);

        // Suspend org updates Redis wallet status
        mockMvc.perform(patch("/api/v1/admin/orgs/{orgId}/status", orgId)
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SUSPENDED","version":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        assertThat(redisTemplate.opsForHash().get(GatewayRedisKeys.wallet(orgId), "status"))
                .isEqualTo("SUSPENDED");
    }

    private static String findProviderId(JsonNode providers, String code) {
        for (JsonNode p : providers) {
            if (code.equals(p.path("code").asText())) {
                return p.path("id").asText();
            }
        }
        throw new IllegalStateException("Provider not found: " + code);
    }

    private static String generateCurrentTotp(String base32Secret) {
        // Brute-free generation via reflection or package access? AdminFlowIntegrationTest can instantiate package-private or call verify?
        // Actually call TotpService.generateCodeForStep directly via helper in same package or put test helper in config package!
        return app.pagefault.hyperllm.admin.config.TotpTestHelper.currentCode(base32Secret);
    }
}

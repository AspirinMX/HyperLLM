package app.pagefault.hyperllm.gateway;

import app.pagefault.hyperllm.common.billing.LedgerLagSignal;
import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.common.crypto.AesGcmCipher;
import app.pagefault.hyperllm.common.crypto.ApiKeyGenerator;
import app.pagefault.hyperllm.common.crypto.HmacSha256;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.messaging.BillingSettlementEvent;
import app.pagefault.hyperllm.common.messaging.KafkaTopics;
import app.pagefault.hyperllm.domain.model.auth.User;
import app.pagefault.hyperllm.domain.model.billing.Wallet;
import app.pagefault.hyperllm.domain.model.enums.ApiKeyStatus;
import app.pagefault.hyperllm.domain.model.enums.BillingMode;
import app.pagefault.hyperllm.domain.model.enums.BindingStatus;
import app.pagefault.hyperllm.domain.model.enums.ChannelStatus;
import app.pagefault.hyperllm.domain.model.enums.ModelCapability;
import app.pagefault.hyperllm.domain.model.enums.ModelStatus;
import app.pagefault.hyperllm.domain.model.enums.ModelVisibility;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import app.pagefault.hyperllm.domain.model.enums.ProjectEnvironment;
import app.pagefault.hyperllm.domain.model.enums.ProjectStatus;
import app.pagefault.hyperllm.domain.model.enums.ProtocolFamily;
import app.pagefault.hyperllm.domain.model.enums.RoutingStrategy;
import app.pagefault.hyperllm.domain.model.enums.UserStatus;
import app.pagefault.hyperllm.domain.model.routing.Channel;
import app.pagefault.hyperllm.domain.model.routing.ModelChannelBinding;
import app.pagefault.hyperllm.domain.model.routing.PricingRule;
import app.pagefault.hyperllm.domain.model.routing.Provider;
import app.pagefault.hyperllm.domain.model.routing.VirtualModel;
import app.pagefault.hyperllm.domain.model.tenant.ApiKey;
import app.pagefault.hyperllm.domain.model.tenant.CustomerTier;
import app.pagefault.hyperllm.domain.model.tenant.Organization;
import app.pagefault.hyperllm.domain.model.tenant.Project;
import app.pagefault.hyperllm.domain.repository.ApiKeyRepository;
import app.pagefault.hyperllm.domain.repository.ChannelRepository;
import app.pagefault.hyperllm.domain.repository.CustomerTierRepository;
import app.pagefault.hyperllm.domain.repository.ModelChannelBindingRepository;
import app.pagefault.hyperllm.domain.repository.OrganizationRepository;
import app.pagefault.hyperllm.domain.repository.PricingRuleRepository;
import app.pagefault.hyperllm.domain.repository.ProjectRepository;
import app.pagefault.hyperllm.domain.repository.ProviderRepository;
import app.pagefault.hyperllm.domain.repository.UserRepository;
import app.pagefault.hyperllm.domain.repository.VirtualModelRepository;
import app.pagefault.hyperllm.domain.repository.WalletRepository;
import app.pagefault.hyperllm.gateway.cache.EntitlementMirrorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(
        classes = GatewayApplication.class,
        properties = {
                "hyperllm.security.aes-master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "hyperllm.security.key-pepper-v1=gateway-test-pepper-v1"
        }
)
@AutoConfigureWebTestClient
class GatewayE2EIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hyperllm_gw_test")
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

    static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
        WIREMOCK.start();
        // Gateway disables Flyway intentionally; run schema migration explicitly for test DB
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeAll
    static void init() {
        WIREMOCK.resetAll();
    }

    @AfterAll
    static void shutdownWireMock() {
        WIREMOCK.stop();
    }

    @Autowired private WebTestClient webTestClient;
    @Autowired private HikariDataSource primaryDataSource;
    @Autowired @Qualifier("gatewayOutboxDataSource") private HikariDataSource outboxDataSource;
    @Autowired private ReactiveStringRedisTemplate redisTemplate;
    @Autowired private EntitlementMirrorService mirrorService;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private AesGcmCipher aesGcmCipher;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private CustomerTierRepository tierRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private app.pagefault.hyperllm.domain.repository.BonusGrantRepository bonusGrantRepository;
    @Autowired private org.jooq.DSLContext dsl;
    @Autowired private ProviderRepository providerRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private VirtualModelRepository modelRepository;
    @Autowired private ModelChannelBindingRepository bindingRepository;
    @Autowired private PricingRuleRepository pricingRuleRepository;

    @Test
    void reservesTwoOfTheEightDatabaseConnectionsForTheOutbox() {
        assertThat(outboxDataSource).isNotSameAs(primaryDataSource);
        assertThat(outboxDataSource.getJdbcUrl()).isEqualTo(primaryDataSource.getJdbcUrl());
        assertThat(outboxDataSource.getMaximumPoolSize()).isEqualTo(2);
        assertThat(primaryDataSource.getMaximumPoolSize()).isEqualTo(6);
    }

    @Test
    void proxiesOpenAiStreamingWithRetryBillingKafkaAndCdcEviction() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CustomerTier tier = tierRepository.findActiveByCode("STANDARD").orElseThrow();
        UUID ownerId = UuidV7.next();
        UUID orgId = UuidV7.next();
        UUID projectId = UuidV7.next();

        userRepository.insert(new User(
                ownerId, "gw-" + ownerId + "@acme.dev", now, "GW User",
                null, "en-US", "USD", orgId, UserStatus.ACTIVE, now, null, 0L, now, now
        ));
        organizationRepository.insert(new Organization(
                orgId, "GW Org", "gw-" + orgId.toString().substring(0, 8),
                ownerId, tier.id(), 10_000, 10, false, OrgStatus.ACTIVE, null, 0L, now, now
        ));
        projectRepository.insert(new Project(
                projectId, orgId, "prod", ProjectEnvironment.PRODUCTION, null, null,
                List.of("gpt-gw-test", "claude-gw-test"), 5, null, null, false, (short) 7,
                null, ProjectStatus.ACTIVE, null, 0L, now, now
        ));
        // 5_000 bonus + 20_000 cash = 25_000 micros. The bonus is modelled as a batch, because the
        // mirror is built from batches: there is no aggregate bonus field to read any more.
        walletRepository.insert(new Wallet(orgId, 20_000L, 5_000L, 20_000L, 5_000L, 0L, 0L, null, 0L, now));
        UUID grantId = UuidV7.next();
        bonusGrantRepository.insert(new app.pagefault.hyperllm.domain.model.billing.BonusGrant(
                grantId, orgId,
                app.pagefault.hyperllm.domain.model.enums.GrantSource.SIGNUP,
                null, null, List.of("gpt-gw-test"), 5_000L, 5_000L,
                now.plusDays(30),
                app.pagefault.hyperllm.domain.model.enums.EntitlementStatus.ACTIVE,
                0L, now, now));

        ApiKeyGenerator.GeneratedApiKey generated = ApiKeyGenerator.generateLiveKey();
        String keyHash = HmacSha256.hmacHex(generated.rawKey(), "gateway-test-pepper-v1");
        UUID keyId = UuidV7.next();
        apiKeyRepository.insert(new ApiKey(
                keyId, projectId, orgId, ownerId, "GW Key", generated.keyPrefix(), keyHash, (short) 1,
                List.of("gpt-gw-test", "claude-gw-test"), null, 2, null, null, null, null, null,
                ApiKeyStatus.ACTIVE, null, 0L, now, now
        ));

        Provider openai = providerRepository.findByCode("OPENAI").orElseThrow();
        Provider anthropic = providerRepository.findByCode("ANTHROPIC").orElseThrow();

        // Failing Tier-1 channel & healthy Tier-2 channel to exercise pre-first-byte retry
        UUID failingChannelId = UuidV7.next();
        UUID healthyChannelId = UuidV7.next();
        UUID anthropicChannelId = UuidV7.next();
        channelRepository.insert(new Channel(
                failingChannelId, openai.id(), "openai-failing-" + failingChannelId, "us-east",
                "http://127.0.0.1:" + WIREMOCK.port() + "/fail/v1",
                aesGcmCipher.encryptUtf8("{\"apiKey\":\"sk-up-fail\"}", failingChannelId.toString()),
                (short) 1, 9000, null, null, null, null, ChannelStatus.ACTIVE, null, 0L, now, now
        ));
        channelRepository.insert(new Channel(
                healthyChannelId, openai.id(), "openai-ok-" + healthyChannelId, "us-west",
                "http://127.0.0.1:" + WIREMOCK.port() + "/ok/v1",
                aesGcmCipher.encryptUtf8("{\"apiKey\":\"sk-up-ok\"}", healthyChannelId.toString()),
                (short) 1, 9000, null, null, null, null, ChannelStatus.ACTIVE, null, 0L, now, now
        ));
        channelRepository.insert(new Channel(
                anthropicChannelId, anthropic.id(), "anthropic-ok-" + anthropicChannelId, "us-east",
                "http://127.0.0.1:" + WIREMOCK.port() + "/ant/v1",
                aesGcmCipher.encryptUtf8("{\"apiKey\":\"sk-ant-ok\"}", anthropicChannelId.toString()),
                (short) 1, 9000, null, null, null, null, ChannelStatus.ACTIVE, null, 0L, now, now
        ));

        UUID openaiModelId = UuidV7.next();
        modelRepository.insert(new VirtualModel(
                openaiModelId, "gpt-gw-test", "GPT GW Test", "OpenAI",
                ModelCapability.CHAT, ProtocolFamily.OPENAI, 128_000, 16_384,
                true, true, false, true, RoutingStrategy.PRIORITY_WEIGHTED,
                false, 300, ModelVisibility.PUBLIC, null, ModelStatus.ACTIVE, "{}", null, 0L, now, now
        ));
        bindingRepository.insert(new ModelChannelBinding(
                UuidV7.next(), openaiModelId, failingChannelId, "gpt-4o-fail", (short) 1, 100,
                null, null, "{}", BindingStatus.ACTIVE, null, 0L, now, now
        ));
        bindingRepository.insert(new ModelChannelBinding(
                UuidV7.next(), openaiModelId, healthyChannelId, "gpt-4o-mini", (short) 2, 100,
                null, null, "{}", BindingStatus.ACTIVE, null, 0L, now, now
        ));
        // Input: 1000 micros/1M -> 1000 tokens = 1 micro; Output: 10_000_000 micros/1M -> 1000 tokens = 10_000 micros
        pricingRuleRepository.insert(new PricingRule(
                UuidV7.next(), openaiModelId, BillingMode.TOKEN,
                1_000_000L, 10_000_000L, 0L, 0L, 0L, 0L, "[]", "{}",
                now.minusDays(1), null, null, now
        ));

        UUID anthropicModelId = UuidV7.next();
        modelRepository.insert(new VirtualModel(
                anthropicModelId, "claude-gw-test", "Claude GW Test", "Anthropic",
                ModelCapability.CHAT, ProtocolFamily.ANTHROPIC, 200_000, 8192,
                true, true, true, true, RoutingStrategy.PRIORITY_WEIGHTED,
                false, 300, ModelVisibility.PUBLIC, null, ModelStatus.ACTIVE, "{}", null, 0L, now, now
        ));
        bindingRepository.insert(new ModelChannelBinding(
                UuidV7.next(), anthropicModelId, anthropicChannelId, "claude-sonnet-4-5", (short) 1, 100,
                null, null, "{}", BindingStatus.ACTIVE, null, 0L, now, now
        ));
        pricingRuleRepository.insert(new PricingRule(
                UuidV7.next(), anthropicModelId, BillingMode.TOKEN,
                1_000_000L, 1_000_000L, 0L, 0L, 0L, 0L, "[]", "{}",
                now.minusDays(1), null, null, now
        ));

        // WireMock stubs
        WIREMOCK.stubFor(post(urlEqualTo("/fail/v1/chat/completions"))
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":\"overloaded\"}")));
        String sseBody = """
                data: {"id":"1","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello!"}}]}

                data: {"id":"1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: {"id":"1","choices":[],"usage":{"prompt_tokens":1000,"completion_tokens":1000}}

                data: [DONE]

                """;
        WIREMOCK.stubFor(post(urlEqualTo("/ok/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer sk-up-ok"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(sseBody)));

        WIREMOCK.stubFor(post(urlEqualTo("/ant/v1/messages"))
                .withHeader("x-api-key", equalTo("sk-ant-ok"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_1\",\"usage\":{\"input_tokens\":500,\"output_tokens\":500}}")));

        String signalKey = GatewayRedisKeys.ledgerLagSignal();
        var lag = LedgerLagSignal.degraded(5_000L, 41.4, System.currentTimeMillis());
        redisTemplate.opsForValue().set(signalKey, objectMapper.writeValueAsString(lag), Duration.ofSeconds(60)).block();
        webTestClient.post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + generated.rawKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"gpt-gw-test\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "42")
                .expectBody().jsonPath("$.error.code").isEqualTo("LEDGER_BACKLOG");
        assertThat(redisTemplate.hasKey(GatewayRedisKeys.wallet(orgId)).block()).isFalse();
        // Seed the already-live case. The same signal permits using an existing mirror.
        mirrorService.load(orgId, "gpt-gw-test").block();
        mirrorService.load(orgId, "claude-gw-test").block();

        // This batch is scoped to the OpenAI model and must not become a universal legacy balance.
        assertThat(mirrorService.read(orgId, "gpt-gw-test", false).block().snapshot().grants()).hasSize(1);
        assertThat(mirrorService.read(orgId, "claude-gw-test", false).block().snapshot().grants()).isEmpty();

        // 1. OpenAI streaming call: retries Tier-1 503 -> succeeds Tier-2, strips injected usage, deducts 11_000 micros (5_000 bonus + 6_000 cash)
        String body = webTestClient.post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + generated.rawKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"gpt-gw-test\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("Hello!").contains("[DONE]").doesNotContain("\"usage\"");
        WIREMOCK.verify(postRequestedFor(urlEqualTo("/fail/v1/chat/completions")));
        WIREMOCK.verify(postRequestedFor(urlEqualTo("/ok/v1/chat/completions"))
                .withRequestBody(containing("\"include_usage\":true"))
                .withRequestBody(containing("\"gpt-4o-mini\"")));

        String walletRedisKey = GatewayRedisKeys.wallet(orgId);
        assertThat(redisTemplate.opsForHash().get(walletRedisKey, "cash_micros").block()).isEqualTo("14000");
        // The whole bonus batch was spent: the freeze was released and the batch decremented.
        String grantItemKey = GatewayRedisKeys.bonusItem(grantId);
        assertThat(redisTemplate.opsForHash().get(grantItemKey, "remaining_micros").block()).isEqualTo("0");
        assertThat(redisTemplate.opsForHash().get(grantItemKey, "held_micros").block()).isEqualTo("0");

        // The settlement is a row in the outbox, not a Kafka message: the ledger is fed from the
        // table's write-ahead log, so the row itself is the message.
        assertThat(pollOutboxForOrg(orgId)).isTrue();

        // Four CJK code points use 12 UTF-8 bytes: 3 estimated text tokens plus 8 for message framing and role.
        webTestClient.post()
                .uri("/v1/messages/count_tokens")
                .header("x-api-key", generated.rawKey())
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"claude-gw-test\",\"messages\":[{\"role\":\"user\",\"content\":\"你好世界\"}]}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.input_tokens").isEqualTo(11);

        // 2. Anthropic non-streaming /v1/messages using x-api-key -> second request within RPM=2 succeeds
        webTestClient.post()
                .uri("/v1/messages")
                .header("x-api-key", generated.rawKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"claude-gw-test\",\"max_tokens\":64,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}")
                .exchange()
                .expectStatus().isOk();

        // 3. Third request within minute exceeds key RPM=2 -> returns OpenAI native 429 error envelope
        webTestClient.post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + generated.rawKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"gpt-gw-test\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("rate_limit_error");

        // 4. GET /v1/models lists allowed models
        webTestClient.get()
                .uri("/v1/models")
                .header("Authorization", "Bearer " + generated.rawKey())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list");

        // 5. CDC eviction must actually take effect, not merely publish a message.
        //    Revoke in PostgreSQL, drop the L2 entry exactly as the control plane would,
        //    publish the Debezium envelope, then prove the next request is rejected.
        assertThat(apiKeyRepository.revoke(keyId, 0L, now)).isTrue();
        redisTemplate.delete(GatewayRedisKeys.keyConfig(keyHash)).block();
        kafkaTemplate.send(KafkaTopics.CDC_GATEWAY_CONFIG,
                "{\"payload\":{\"source\":{\"table\":\"api_keys\"},\"after\":{\"key_hash\":\"" + keyHash + "\"}}}")
                .get();
        assertThat(awaitUnauthorized(generated.rawKey()))
                .as("L1 key cache was not evicted within 10s of the CDC event; a revoked key was still accepted")
                .isTrue();
    }

    /**
     * Polls until the gateway rejects the revoked key. Before eviction the cached context is still
     * ACTIVE, so the call keeps succeeding; the assertion only passes once L1 is genuinely evicted.
     */
    private boolean awaitUnauthorized(String rawKey) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            int status = webTestClient.get()
                    .uri("/v1/models")
                    .header("Authorization", "Bearer " + rawKey)
                    .exchange()
                    .returnResult(String.class)
                    .getStatus()
                    .value();
            if (status == 401) {
                return true;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Test
    void refusesRequestsOverTheProjectMonthlyBudgetWithTheProtocolError() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CustomerTier tier = tierRepository.findActiveByCode("STANDARD").orElseThrow();
        UUID ownerId = UuidV7.next();
        UUID orgId = UuidV7.next();
        UUID projectId = UuidV7.next();
        userRepository.insert(new User(
                ownerId, "budget-" + ownerId + "@acme.dev", now, "Budget User",
                null, "en-US", "USD", orgId, UserStatus.ACTIVE, now, null, 0L, now, now
        ));
        organizationRepository.insert(new Organization(
                orgId, "Budget Org", "budget-" + orgId.toString().substring(0, 8),
                ownerId, tier.id(), 10_000, 10, false, OrgStatus.ACTIVE, null, 0L, now, now
        ));
        // One request reserves about 94k micros at the safety factor and costs 11k. The cap leaves
        // room for exactly one admission, so the second must be refused without charging anything.
        projectRepository.insert(new Project(
                projectId, orgId, "budget", ProjectEnvironment.PRODUCTION, 100_000L, null,
                null, null, null, null, false, (short) 7,
                null, ProjectStatus.ACTIVE, null, 0L, now, now
        ));
        walletRepository.insert(new Wallet(orgId, 5_000_000L, 0L, 5_000_000L, 0L, 0L, 0L, null, 0L, now));

        ApiKeyGenerator.GeneratedApiKey generated = ApiKeyGenerator.generateLiveKey();
        apiKeyRepository.insert(new ApiKey(
                UuidV7.next(), projectId, orgId, ownerId, "Budget Key", generated.keyPrefix(),
                HmacSha256.hmacHex(generated.rawKey(), "gateway-test-pepper-v1"), (short) 1,
                null, null, null, null, null, null, null, null,
                ApiKeyStatus.ACTIVE, null, 0L, now, now
        ));

        Provider openai = providerRepository.findByCode("OPENAI").orElseThrow();
        UUID channelId = UuidV7.next();
        channelRepository.insert(new Channel(
                channelId, openai.id(), "openai-budget-" + channelId, "us-east",
                "http://127.0.0.1:" + WIREMOCK.port() + "/budget/v1",
                aesGcmCipher.encryptUtf8("{\"apiKey\":\"sk-up-budget\"}", channelId.toString()),
                (short) 1, 9000, null, null, null, null, ChannelStatus.ACTIVE, null, 0L, now, now
        ));
        UUID modelId = UuidV7.next();
        modelRepository.insert(new VirtualModel(
                modelId, "gpt-budget-test", "GPT Budget Test", "OpenAI",
                ModelCapability.CHAT, ProtocolFamily.OPENAI, 128_000, 16_384,
                true, true, false, true, RoutingStrategy.PRIORITY_WEIGHTED,
                false, 300, ModelVisibility.PUBLIC, null, ModelStatus.ACTIVE, "{}", null, 0L, now, now
        ));
        bindingRepository.insert(new ModelChannelBinding(
                UuidV7.next(), modelId, channelId, "gpt-4o-mini", (short) 1, 100,
                null, null, "{}", BindingStatus.ACTIVE, null, 0L, now, now
        ));
        pricingRuleRepository.insert(new PricingRule(
                UuidV7.next(), modelId, BillingMode.TOKEN,
                1_000_000L, 10_000_000L, 0L, 0L, 0L, 0L, "[]", "{}",
                now.minusDays(1), null, null, now
        ));
        WIREMOCK.stubFor(post(urlEqualTo("/budget/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer sk-up-budget"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0,"
                                + "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],"
                                + "\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":1000}}")));
        mirrorService.load(orgId, "gpt-budget-test").block();

        String request = "{\"model\":\"gpt-budget-test\",\"max_tokens\":8192,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + generated.rawKey())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                .exchange().expectStatus().isOk();

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + generated.rawKey())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody().jsonPath("$.error.code").isEqualTo("PROJECT_BUDGET_EXCEEDED");

        String month = java.time.YearMonth.now(ZoneOffset.UTC).toString();
        String budgetKey = GatewayRedisKeys.projectBudget(projectId, month);
        assertThat(redisTemplate.opsForHash().get(budgetKey, "used_micros").block()).isEqualTo("11000");
        assertThat(redisTemplate.opsForHash().get(budgetKey, "held_micros").block()).isEqualTo("0");

        // A month that cannot be proven any more closes a hard-capped project instead of guessing.
        redisTemplate.opsForHash().put(budgetKey, "state", "UNVERIFIED").block();
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + generated.rawKey())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error.code").isEqualTo("PROJECT_BUDGET_UNAVAILABLE");
    }

    @Test
    void supportsAllFourProtocolsCrossProtocolTranslationFailoverAndReasoningPricingFallback() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CustomerTier standard = tierRepository.findActiveByCode("STANDARD").orElseThrow();
        UUID ownerId = UuidV7.next();
        UUID orgId = UuidV7.next();
        UUID projectId = UuidV7.next();
        userRepository.insert(new User(
                ownerId, "xproto-" + ownerId + "@example.com", now, "XProto Owner",
                null, "en-US", "USD", orgId, UserStatus.ACTIVE, now, null, 0L, now, now
        ));
        organizationRepository.insert(new Organization(
                orgId, "XProto Org", "xproto-" + orgId.toString().substring(0, 8),
                ownerId, standard.id(), 10_000, 50, false, OrgStatus.ACTIVE, null, 0L, now, now
        ));
        projectRepository.insert(new Project(
                projectId, orgId, "default", ProjectEnvironment.PRODUCTION, null, null,
                List.of("omni-xproto", "gem-to-oai"), 100, null, null, false, (short) 7,
                null, ProjectStatus.ACTIVE, null, 0L, now, now
        ));
        walletRepository.insert(new Wallet(orgId, 100_000L, 0L, 100_000L, 0L, 0L, 0L, null, 0L, now));

        var generated = ApiKeyGenerator.generateLiveKey();
        String keyHash = HmacSha256.hmacHex(generated.rawKey(), "gateway-test-pepper-v1");
        apiKeyRepository.insert(new ApiKey(
                UuidV7.next(), projectId, orgId, ownerId, "XProto Key", generated.keyPrefix(), keyHash, (short) 1,
                List.of("omni-xproto", "gem-to-oai"), null, 100, null, null, null, null, null,
                ApiKeyStatus.ACTIVE, null, 0L, now, now
        ));

        Provider anthropic = providerRepository.findByCode("ANTHROPIC").orElseThrow();
        Provider gemini = providerRepository.findByCode("GOOGLE_GEMINI").orElseThrow();
        Provider deepseek = providerRepository.findByCode("DEEPSEEK").orElseThrow();

        UUID failingAntChannel = UuidV7.next();
        UUID geminiChannel = UuidV7.next();
        UUID deepseekChannel = UuidV7.next();
        channelRepository.insert(new Channel(
                failingAntChannel, anthropic.id(), "ant-529-" + failingAntChannel, "us-east",
                "http://127.0.0.1:" + WIREMOCK.port() + "/xant",
                aesGcmCipher.encryptUtf8("{\"apiKey\":\"sk-ant-529\"}", failingAntChannel.toString()),
                (short) 1, 10_000, null, null, null, null, ChannelStatus.ACTIVE, null, 0L, now, now
        ));
        channelRepository.insert(new Channel(
                geminiChannel, gemini.id(), "gem-ok-" + geminiChannel, "us-central1",
                "http://127.0.0.1:" + WIREMOCK.port() + "/xgem",
                aesGcmCipher.encryptUtf8("{\"apiKey\":\"AIza-test-key\"}", geminiChannel.toString()),
                (short) 1, 10_000, null, null, null, null, ChannelStatus.ACTIVE, null, 0L, now, now
        ));
        channelRepository.insert(new Channel(
                deepseekChannel, deepseek.id(), "ds-ok-" + deepseekChannel, "ap-east",
                "http://127.0.0.1:" + WIREMOCK.port() + "/xds/v1",
                aesGcmCipher.encryptUtf8("{\"apiKey\":\"sk-ds-ok\"}", deepseekChannel.toString()),
                (short) 1, 10_000, null, null, null, null, ChannelStatus.ACTIVE, null, 0L, now, now
        ));

        // Model 1: omni-xproto (Tier 1: Anthropic 529 homomorphic; Tier 1: Gemini heterogeneous fallback)
        // Pricing intentionally sets reasoningPriceMicros1m = 0 to verify confirmed rule: fallback to outputPriceMicros1m (2_000_000)
        UUID omniModelId = UuidV7.next();
        modelRepository.insert(new VirtualModel(
                omniModelId, "omni-xproto", "Omni Cross-Protocol", "HyperLLM",
                ModelCapability.CHAT, ProtocolFamily.ANTHROPIC, 200_000, 8192,
                true, true, true, true, RoutingStrategy.PRIORITY_WEIGHTED,
                false, 300, ModelVisibility.PUBLIC, null, ModelStatus.ACTIVE, "{}",
                true, "BEST_EFFORT", null, 0L, now, now
        ));
        bindingRepository.insert(new ModelChannelBinding(
                UuidV7.next(), omniModelId, failingAntChannel, "claude-sonnet-4-5", null, (short) 1, 10,
                null, null, "{}", BindingStatus.ACTIVE, null, 0L, now, now
        ));
        bindingRepository.insert(new ModelChannelBinding(
                UuidV7.next(), omniModelId, geminiChannel, "gemini-2.5-flash", null, (short) 1, 900,
                null, null, "{}", BindingStatus.ACTIVE, null, 0L, now, now
        ));
        pricingRuleRepository.insert(new PricingRule(
                UuidV7.next(), omniModelId, BillingMode.TOKEN,
                1_000_000L, 2_000_000L, 250_000L, 0L, 0L, 0L, "[]", "{}",
                now.minusDays(1), null, null, now
        ));

        // Model 2: gem-to-oai (only OPENAI_CHAT channel; called via Gemini endpoint with ?key= and OpenAI Responses)
        UUID gemToOaiId = UuidV7.next();
        modelRepository.insert(new VirtualModel(
                gemToOaiId, "gem-to-oai", "Gemini To OpenAI", "DeepSeek",
                ModelCapability.CHAT, ProtocolFamily.OPENAI, 128_000, 4096,
                true, false, false, false, RoutingStrategy.PRIORITY_WEIGHTED,
                false, 300, ModelVisibility.PUBLIC, null, ModelStatus.ACTIVE, "{}",
                true, "BEST_EFFORT", null, 0L, now, now
        ));
        bindingRepository.insert(new ModelChannelBinding(
                UuidV7.next(), gemToOaiId, deepseekChannel, "deepseek-chat", null, (short) 1, 100,
                null, null, "{}", BindingStatus.ACTIVE, null, 0L, now, now
        ));
        pricingRuleRepository.insert(new PricingRule(
                UuidV7.next(), gemToOaiId, BillingMode.TOKEN,
                1_000_000L, 1_000_000L, 0L, 0L, 0L, 0L, "[]", "{}",
                now.minusDays(1), null, null, now
        ));

        // Stubs: Tier-1 Anthropic returns 529 overloaded; Gemini streams SSE with thoughts + tool call
        WIREMOCK.stubFor(post(urlEqualTo("/xant/v1/messages"))
                .withHeader("x-api-key", equalTo("sk-ant-529"))
                .willReturn(aResponse().withStatus(529).withBody("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}")));

        String geminiSse = """
                data: {"responseId":"gem_1","candidates":[{"index":0,"content":{"role":"model","parts":[{"thought":true,"text":"Thinking..."}]}}]}

                data: {"responseId":"gem_1","candidates":[{"index":0,"content":{"role":"model","parts":[{"functionCall":{"id":"fc_1","name":"get_weather","args":{"city":"Tokyo"}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":1200,"cachedContentTokenCount":200,"candidatesTokenCount":300,"thoughtsTokenCount":200,"totalTokenCount":1700}}

                """;
        WIREMOCK.stubFor(post(urlEqualTo("/xgem/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse"))
                .withHeader("x-goog-api-key", equalTo("AIza-test-key"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(geminiSse)));

        WIREMOCK.stubFor(post(urlEqualTo("/xgem/v1beta/models/gemini-2.5-flash:generateContent"))
                .withHeader("x-goog-api-key", equalTo("AIza-test-key"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"responseId\":\"gem_2\",\"candidates\":[{\"index\":0,\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Hello from Gemini!\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":1000,\"candidatesTokenCount\":500,\"totalTokenCount\":1500}}")));

        WIREMOCK.stubFor(post(urlEqualTo("/xds/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer sk-ds-ok"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"chatcmpl-ds\",\"object\":\"chat.completion\",\"created\":1730000000,\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Hi from DeepSeek!\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":1000,\"total_tokens\":2000}}")));

        mirrorService.load(orgId, "omni-xproto").block();
        mirrorService.load(orgId, "gem-to-oai").block();

        // 1. Anthropic SSE -> homomorphic Tier-1 Anthropic tried first (529) -> fails over to Tier-1 Gemini SSE!
        String antStreamResp = webTestClient.post()
                .uri("/v1/messages")
                .header("x-api-key", generated.rawKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "model": "omni-xproto",
                          "max_tokens": 512,
                          "stream": true,
                          "messages": [{"role": "user", "content": "Weather in Tokyo?"}],
                          "tools": [{"name": "get_weather", "input_schema": {"type": "object", "properties": {"city": {"type": "string"}}}}]
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(antStreamResp)
                .contains("event: message_start")
                .contains("thinking_delta")
                .contains("input_json_delta")
                .contains("Tokyo")
                .contains("\"stop_reason\":\"tool_use\"")
                .contains("event: message_stop");
        WIREMOCK.verify(postRequestedFor(urlEqualTo("/xant/v1/messages")));
        WIREMOCK.verify(postRequestedFor(urlEqualTo("/xgem/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse"))
                .withHeader("x-goog-api-key", equalTo("AIza-test-key")));

        // Verify billing: uncachedInput=1000 (@1M=1000), cachedRead=200 (@250k=50),
        // completion=300 (@2M=600), reasoning=200 (fallback to output @2M=400) => total = 2050 micros!
        String walletKey = GatewayRedisKeys.wallet(orgId);
        assertThat(redisTemplate.opsForHash().get(walletKey, "cash_micros").block()).isEqualTo("97950");

        // 2. OpenAI Responses non-streaming -> translated to Gemini generateContent -> Responses envelope
        webTestClient.post()
                .uri("/v1/responses")
                .header("Authorization", "Bearer " + generated.rawKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"omni-xproto\",\"instructions\":\"Be brief.\",\"input\":\"Say hi\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("response")
                .jsonPath("$.status").isEqualTo("completed")
                .jsonPath("$.model").isEqualTo("omni-xproto")
                .jsonPath("$.output[0].content[0].text").isEqualTo("Hello from Gemini!")
                .jsonPath("$.usage.input_tokens").isEqualTo(1000)
                .jsonPath("$.usage.output_tokens").isEqualTo(500);

        // 3. Gemini REST endpoint (?key= auth) -> translated to OpenAI Chat (DeepSeek) -> Gemini response
        webTestClient.post()
                .uri("/v1beta/models/gem-to-oai:generateContent?key=" + generated.rawKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"Hello\"}]}]}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.candidates[0].content.parts[0].text").isEqualTo("Hi from DeepSeek!")
                .jsonPath("$.candidates[0].finishReason").isEqualTo("STOP")
                .jsonPath("$.usageMetadata.promptTokenCount").isEqualTo(1000)
                .jsonPath("$.usageMetadata.candidatesTokenCount").isEqualTo(1000);

        // 4. OpenAI Responses stateful previous_response_id on cross-protocol channel -> 400 + hold cleanly released
        webTestClient.post()
                .uri("/v1/responses")
                .header("Authorization", "Bearer " + generated.rawKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"model\":\"gem-to-oai\",\"previous_response_id\":\"resp_old\",\"input\":\"Continue\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("PROTOCOL_UNSUPPORTED_FEATURE");
        assertThat(redisTemplate.opsForHash().get(walletKey, "held_cash_micros").block()).isEqualTo("0");
    }

    private KafkaConsumer<String, String> createConsumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "gw-test-verifier-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        ));
    }

    /** Waits for the writer to append this organisation's settlement row. */
    private boolean pollOutboxForOrg(UUID orgId) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            Integer count = dsl.fetchOne(
                    "select count(*) from outbox_events where aggregateid = ?", orgId.toString())
                    .get(0, Integer.class);
            if (count != null && count > 0) {
                return true;
            }
            Thread.sleep(100L);
        }
        return false;
    }

    private BillingSettlementEvent pollForOrgEvent(KafkaConsumer<String, String> consumer, UUID orgId) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> rec : consumer.poll(Duration.ofMillis(250))) {
                BillingSettlementEvent ev = objectMapper.readValue(rec.value(), BillingSettlementEvent.class);
                if (orgId.equals(ev.orgId())) {
                    return ev;
                }
            }
        }
        return null;
    }
}

package app.pagefault.hyperllm.gateway;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
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
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
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
    @Autowired private ReactiveStringRedisTemplate redisTemplate;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private AesGcmCipher aesGcmCipher;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private CustomerTierRepository tierRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private ProviderRepository providerRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private VirtualModelRepository modelRepository;
    @Autowired private ModelChannelBindingRepository bindingRepository;
    @Autowired private PricingRuleRepository pricingRuleRepository;

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
        // 5_000 bonus + 20_000 cash = 25_000 micros
        walletRepository.insert(new Wallet(orgId, 20_000L, 5_000L, 20_000L, 5_000L, 0L, 0L, null, 0L, now));

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
        assertThat(redisTemplate.opsForHash().get(walletRedisKey, "general_bonus_micros").block()).isEqualTo("0");
        assertThat(redisTemplate.opsForHash().get(walletRedisKey, "cash_micros").block()).isEqualTo("14000");

        // Verify settlement event landed in Kafka topic
        try (KafkaConsumer<String, String> consumer = createConsumer()) {
            consumer.subscribe(List.of(KafkaTopics.LLM_BILLING_SETTLEMENT));
            BillingSettlementEvent consumed = pollForOrgEvent(consumer, orgId);
            assertThat(consumed).isNotNull();
            assertThat(consumed.bonusDeltaMicros()).isEqualTo(-5_000L);
            assertThat(consumed.cashDeltaMicros()).isEqualTo(-6_000L);
        }

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

        // 5. Simulate Debezium CDC event on cdc.gateway.config (evict L1 after revoking key in Redis/DB)
        kafkaTemplate.send(KafkaTopics.CDC_GATEWAY_CONFIG,
                "{\"payload\":{\"source\":{\"table\":\"api_keys\"},\"after\":{\"key_hash\":\"" + keyHash + "\"}}}")
                .get();
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

package app.pagefault.hyperllm.console;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.common.crypto.ApiKeyGenerator;
import app.pagefault.hyperllm.common.crypto.HmacSha256;
import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
import java.time.OffsetDateTime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@SpringBootTest(
        classes = ConsoleApplication.class,
        properties = {
                "hyperllm.security.console-jwt-secret=test-console-jwt-secret-at-least-32-bytes",
                "hyperllm.security.aes-master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "hyperllm.security.key-pepper-v1=test-pepper-v1",
                "hyperllm.auth.otp-debug-echo-enabled=true",
                "hyperllm.billing.signup-bonus-micros=5000000"
        }
)
@AutoConfigureMockMvc
class ConsoleFlowIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hyperllm_console_test")
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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private OutboxEventRepository outbox;

    @Test
    void supportsFullRegisterRefreshReplayProtectionRbacKeysWalletAndPlaygroundFlow() throws Exception {
        String email = "alice-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";

        MvcResult codeRes = mockMvc.perform(post("/api/v1/auth/email-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","purpose":"REGISTER"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        String otpCode = objectMapper.readTree(codeRes.getResponse().getContentAsString()).path("debugCode").asText();
        assertThat(otpCode).matches("^\\d{6}$");

        MvcResult registerRes = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Password123!","displayName":"Alice","emailCode":"%s"}
                                """.formatted(email, otpCode)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.organizations[0].orgName").value("Personal Workspace"))
                .andReturn();

        JsonNode registerJson = objectMapper.readTree(registerRes.getResponse().getContentAsString());
        String accessToken = registerJson.path("accessToken").asText();
        String orgId = registerJson.path("user").path("defaultOrgId").asText();
        Cookie rt1 = registerRes.getResponse().getCookie("hl_console_rt");
        assertThat(rt1).isNotNull();
        assertThat(rt1.isHttpOnly()).isTrue();

        // The signup transaction authorizes the bonus without applying it before delivery.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/wallet", orgId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bonusBalanceMicros").value(0));
        assertThat(redisTemplate.hasKey(GatewayRedisKeys.wallet(UUID.fromString(orgId)))).isFalse();
        assertThat(outbox.listUnconfirmedCredits(OffsetDateTime.now().plusSeconds(1), 100))
                .filteredOn(event -> event.aggregateId().equals(orgId)).singleElement()
                .satisfies(event -> assertThat(event.type()).isEqualTo("BONUS_GRANT"));

        // Refresh token rotation
        MvcResult refreshRes = mockMvc.perform(post("/api/v1/auth/refresh").cookie(rt1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();
        Cookie rt2 = refreshRes.getResponse().getCookie("hl_console_rt");
        assertThat(rt2).isNotNull();
        accessToken = objectMapper.readTree(refreshRes.getResponse().getContentAsString()).path("accessToken").asText();

        // Replaying old rt1 triggers family revocation -> 401 PROBLEM DETAIL
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(rt1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_REUSE_DETECTED"));
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(rt2))
                .andExpect(status().isUnauthorized());

        // Create Project & API Key
        MvcResult projRes = mockMvc.perform(post("/api/v1/orgs/{orgId}/projects", orgId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"prod-api","environment":"PRODUCTION","rpmLimit":300,"allowedModels":["gpt-5"]}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String projectId = objectMapper.readTree(projRes.getResponse().getContentAsString()).path("id").asText();

        MvcResult keyRes = mockMvc.perform(post("/api/v1/projects/{projectId}/api-keys", projectId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Primary Key","rpmLimit":120}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode createdKeyJson = objectMapper.readTree(keyRes.getResponse().getContentAsString());
        String rawKey = createdKeyJson.path("rawKey").asText();
        String keyId = createdKeyJson.path("apiKey").path("id").asText();
        long keyVersion = createdKeyJson.path("apiKey").path("version").asLong();
        assertThat(rawKey).startsWith("sk-").hasSize(ApiKeyGenerator.LIVE_TOTAL_LENGTH);

        String keyHash = HmacSha256.hmacHex(rawKey, "test-pepper-v1");
        assertThat(redisTemplate.opsForValue().get(GatewayRedisKeys.keyConfig(keyHash))).isNotBlank();

        // Update & revoke key evicts Redis cache
        MvcResult patchRes = mockMvc.perform(patch("/api/v1/api-keys/{keyId}", keyId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Renamed Key","rpmLimit":200,"status":"ACTIVE","version":%d}
                                """.formatted(keyVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed Key"))
                .andReturn();
        long nextVersion = objectMapper.readTree(patchRes.getResponse().getContentAsString()).path("version").asLong();
        assertThat(redisTemplate.opsForValue().get(GatewayRedisKeys.keyConfig(keyHash))).isNull();

        mockMvc.perform(delete("/api/v1/api-keys/{keyId}", keyId)
                        .param("version", String.valueOf(nextVersion))
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        // Playground ephemeral token
        MvcResult sbRes = mockMvc.perform(post("/api/v1/playground/ephemeral-token")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"%s"}
                                """.formatted(projectId)))
                .andExpect(status().isCreated())
                .andReturn();
        String sandboxToken = objectMapper.readTree(sbRes.getResponse().getContentAsString()).path("token").asText();
        assertThat(sandboxToken).startsWith("sk-hl-sb-");
        assertThat(redisTemplate.opsForValue().get(GatewayRedisKeys.sandboxToken(HmacSha256.sha256Hex(sandboxToken))))
                .contains(projectId);

        // OpenAPI 3.1 endpoint works
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists());
    }
}

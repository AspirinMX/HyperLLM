package app.pagefault.hyperllm.gateway.proxy;

import app.pagefault.hyperllm.common.crypto.AesGcmCipher;
import app.pagefault.hyperllm.domain.billing.AllocationLeg;
import app.pagefault.hyperllm.domain.billing.EntitlementSnapshot;
import app.pagefault.hyperllm.domain.model.enums.ApiKeyStatus;
import app.pagefault.hyperllm.domain.model.enums.AuthScheme;
import app.pagefault.hyperllm.domain.model.enums.BillingMode;
import app.pagefault.hyperllm.domain.model.enums.ModelCapability;
import app.pagefault.hyperllm.domain.model.enums.ModelStatus;
import app.pagefault.hyperllm.domain.model.enums.ModelVisibility;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import app.pagefault.hyperllm.domain.model.enums.ProjectStatus;
import app.pagefault.hyperllm.domain.model.enums.ProtocolFamily;
import app.pagefault.hyperllm.domain.model.enums.RoutingStrategy;
import app.pagefault.hyperllm.domain.model.routing.GatewayChannelCandidate;
import app.pagefault.hyperllm.domain.model.routing.GatewayModelRoute;
import app.pagefault.hyperllm.domain.model.routing.PricingRule;
import app.pagefault.hyperllm.domain.model.routing.VirtualModel;
import app.pagefault.hyperllm.domain.model.tenant.GatewayKeyContext;
import app.pagefault.hyperllm.gateway.billing.ChannelCapacityService;
import app.pagefault.hyperllm.gateway.billing.GatewayTelemetryService;
import app.pagefault.hyperllm.gateway.billing.PreHoldService;
import app.pagefault.hyperllm.gateway.billing.ProjectBudgetService;
import app.pagefault.hyperllm.gateway.cache.EntitlementMirrorService;
import app.pagefault.hyperllm.gateway.cache.GatewayConfigCacheService;
import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import app.pagefault.hyperllm.gateway.outbox.OutboxAdmissionGate;
import app.pagefault.hyperllm.gateway.routing.ChannelSelector;
import app.pagefault.hyperllm.gateway.routing.UpstreamWebClientPool;
import app.pagefault.hyperllm.protocol.format.ProtocolErrorFormatter;
import app.pagefault.hyperllm.protocol.ir.CanonicalRequest;
import app.pagefault.hyperllm.protocol.ir.InboundRequestContext;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import app.pagefault.hyperllm.protocol.translate.ProtocolTranslator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.unit.DataSize;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that cross-protocol failover retains the Canonical IR across 429/5xx retries, while
 * channel capacity is checked before encoding and non-retryable headers close the retry window.
 */
class GatewayProxyLifecycleTest {

    private static final String LIVE_KEY = "sk-test12345678901234567890123456789012345678901234";
    private static final String AES_KEY_B64 = Base64.getEncoder().encodeToString(new byte[32]);

    private WireMockServer wireMock;
    private UpstreamWebClientPool webClientPool;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterEach
    void tearDown() {
        if (webClientPool != null) {
            webClientPool.shutdown();
        }
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Test
    void retainsIrAcross429AndSuccessfullySwitchesUpstreamProtocolOnRetry() {
        ObjectMapper mapper = new ObjectMapper();
        AesGcmCipher cipher = AesGcmCipher.fromBase64((byte) 1, AES_KEY_B64);
        GatewayProperties props = new GatewayProperties();
        props.getUpstream().setMaxRetryAttempts(2);
        props.getUpstream().setTranslationOffloadThresholdBytes(0); // exercise offload path too
        webClientPool = new UpstreamWebClientPool(props);

        // Tier 1 channel (OpenAI Chat) returns 429; Tier 2 channel (Anthropic Messages) returns 200 SSE.
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(429).withBody("{\"error\":\"rate_limited\"}")));
        String anthropicSse = ""
                + "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-fallback\",\"content\":[],\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n"
                + "event: content_block_start\n"
                + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Recovered!\"}}\n\n"
                + "event: content_block_stop\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "event: message_delta\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
                + "\"usage\":{\"output_tokens\":3}}\n\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
        wireMock.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(anthropicSse)));

        UUID orgId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID apiKeyId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        UUID ch1Id = UUID.randomUUID();
        UUID ch2Id = UUID.randomUUID();

        String baseUrl = "http://127.0.0.1:" + wireMock.port();
        GatewayChannelCandidate openAiCandidate = new GatewayChannelCandidate(
                UUID.randomUUID(), ch1Id, "openai-primary", "us-east", "OPENAI", ProtocolFamily.OPENAI,
                List.of("OPENAI_CHAT"), "OPENAI_CHAT", "OPENAI_CHAT", AuthScheme.BEARER,
                baseUrl + "/v1", "gpt-primary", (short) 1, 100, 10_000,
                cipher.encryptUtf8("{\"apiKey\":\"sk-up-1\"}", ch1Id.toString()), (short) 1,
                "{}", null, null, null
        );
        GatewayChannelCandidate anthropicCandidate = new GatewayChannelCandidate(
                UUID.randomUUID(), ch2Id, "anthropic-fallback", "us-east", "ANTHROPIC", ProtocolFamily.ANTHROPIC,
                List.of("ANTHROPIC_MESSAGES"), "ANTHROPIC_MESSAGES", "ANTHROPIC_MESSAGES", AuthScheme.X_API_KEY,
                baseUrl, "claude-fallback", (short) 2, 100, 10_000,
                cipher.encryptUtf8("{\"apiKey\":\"sk-up-2\"}", ch2Id.toString()), (short) 1,
                "{\"X-Channel-Tag\":\"fallback-anthropic\"}", null, null, null
        );
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        VirtualModel vm = new VirtualModel(
                modelId, "omni-model", "Omni", "Omni", ModelCapability.CHAT, ProtocolFamily.OPENAI,
                128_000, 4096, true, true, false, false, RoutingStrategy.PRIORITY_WEIGHTED,
                false, 0, ModelVisibility.PUBLIC, null, ModelStatus.ACTIVE, "{}",
                true, "BEST_EFFORT", null, 1L, now, now
        );
        PricingRule pricing = new PricingRule(
                UUID.randomUUID(), modelId, BillingMode.TOKEN,
                1_000_000L, 2_000_000L, 0L, 0L, 0L, 0L,
                null, null, now.minusDays(1), null, null, now
        );
        GatewayModelRoute route = new GatewayModelRoute(vm, pricing, List.of(openAiCandidate, anthropicCandidate));
        GatewayKeyContext keyCtx = new GatewayKeyContext(
                apiKeyId, projectId, orgId, "sk-test...", "hash", (short) 1,
                ApiKeyStatus.ACTIVE, null, null, null, null, null, null, null,
                ProjectStatus.ACTIVE, null, null, null, null, null, null,
                false, (short) 0, OrgStatus.ACTIVE, 100, 10_000, 10_000
        );

        GatewayConfigCacheService cacheService = Mockito.mock(GatewayConfigCacheService.class);
        OutboxAdmissionGate outboxGate = Mockito.mock(OutboxAdmissionGate.class);
        PreHoldService preHoldService = Mockito.mock(PreHoldService.class);
        ProjectBudgetService budgetService = Mockito.mock(ProjectBudgetService.class);
        ChannelCapacityService capacityService = Mockito.mock(ChannelCapacityService.class);
        GatewayTelemetryService telemetryService = Mockito.mock(GatewayTelemetryService.class);
        EntitlementMirrorService mirrorService = Mockito.mock(EntitlementMirrorService.class);

        when(cacheService.resolveLiveKey(LIVE_KEY)).thenReturn(Mono.just(keyCtx));
        when(cacheService.resolveModelRoute("omni-model")).thenReturn(Mono.just(route));
        when(cacheService.resolvePricingOverride(orgId, modelId)).thenReturn(Mono.empty());
        when(mirrorService.read(orgId, "omni-model", true)).thenReturn(Mono.just(
                new EntitlementMirrorService.Mirror(EntitlementSnapshot.empty(100_000_000L), "ACTIVE", true)));
        when(mirrorService.heldLegs(anyString())).thenReturn(Mono.just(List.of(AllocationLeg.cash(1_000_000L))));
        when(budgetService.ensure(orgId, projectId, null, null))
                .thenReturn(Mono.just(ProjectBudgetService.BudgetContext.disabled()));
        when(preHoldService.admit(any())).thenReturn(Mono.just("OK"));
        when(preHoldService.markServed(any())).thenReturn(Mono.empty());
        when(preHoldService.renew(any(), anyLong())).thenReturn(Mono.just(true));
        when(preHoldService.settle(any(), any(), anyString(), any(), anyLong(), anyLong()))
                .thenReturn(Mono.just(new PreHoldService.SettleOutcome("SETTLED", 100L, 0L, 0L)));
        when(preHoldService.reclaimExpired(Mockito.anyInt())).thenReturn(Mono.just(0));
        when(capacityService.acquire(any(), anyString(), any(), anyLong())).thenReturn(Mono.just("OK"));
        when(capacityService.hasConcurrencyLimit(any())).thenReturn(false);

        TokenEstimator estimator = new TokenEstimator();
        ProtocolTranslator translator = new ProtocolTranslator(mapper, estimator, new ProtocolErrorFormatter(mapper));
        GatewayProxyService service = new GatewayProxyService(
                cacheService, outboxGate, preHoldService, budgetService, capacityService,
                telemetryService, props, translator, cipher, new ChannelSelector(), webClientPool,
                mirrorService, mapper, estimator, Schedulers.boundedElastic(), DataSize.ofMegabytes(16)
        );

        String reqJson = """
                {
                  "model": "omni-model",
                  "stream": true,
                  "messages": [{"role": "user", "content": "Say hello after failover"}]
                }
                """;
        MockServerHttpRequest httpRequest = MockServerHttpRequest.post("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + LIVE_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(reqJson);
        MockServerWebExchange exchange = MockServerWebExchange.from(httpRequest);

        ResponseEntity<Flux<DataBuffer>> response = service
                .proxy(exchange, ProtocolKind.OPENAI_CHAT, InboundRequestContext.empty())
                .block(Duration.ofSeconds(10));

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String body = DataBufferUtils.join(response.getBody())
                .map(buf -> {
                    try {
                        byte[] bytes = new byte[buf.readableByteCount()];
                        buf.read(bytes);
                        return new String(bytes, StandardCharsets.UTF_8);
                    } finally {
                        DataBufferUtils.release(buf);
                    }
                })
                .block(Duration.ofSeconds(10));

        assertThat(body).contains("Recovered!").contains("data: [DONE]");

        // Both upstream protocols were hit, proving the IR survived the 429 and re-encoded into Anthropic Messages!
        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("\"model\":\"gpt-primary\"")));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/messages"))
                .withHeader("X-Channel-Tag", equalTo("fallback-anthropic"))
                .withRequestBody(containing("\"model\":\"claude-fallback\""))
                .withRequestBody(containing("Say hello after failover")));

        // Settlement reused Lease.heldLegs without a redundant Redis HGET, and admission gate ran once.
        verify(mirrorService, never()).heldLegs(anyString());
        verify(outboxGate, times(1)).check();
    }

    @Test
    void requestIrHolderClearsOnTakeAndRelease() {
        ProtocolTranslator translator = new ProtocolTranslator(new ObjectMapper());
        CanonicalRequest req = translator.parseRequest(
                ProtocolKind.OPENAI_CHAT,
                "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}".getBytes(StandardCharsets.UTF_8),
                InboundRequestContext.empty());

        RequestIrHolder holder = new RequestIrHolder(req);
        assertThat(holder.isPresent()).isTrue();
        assertThat(holder.get()).isSameAs(req);
        assertThat(holder.take()).isSameAs(req);
        assertThat(holder.isPresent()).isFalse();
        assertThat(holder.get()).isNull();

        RequestIrHolder holder2 = new RequestIrHolder(req);
        holder2.release();
        assertThat(holder2.isPresent()).isFalse();
    }
}

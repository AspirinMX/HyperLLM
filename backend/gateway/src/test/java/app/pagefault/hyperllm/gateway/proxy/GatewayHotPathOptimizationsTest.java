package app.pagefault.hyperllm.gateway.proxy;

import app.pagefault.hyperllm.domain.model.enums.AuthScheme;
import app.pagefault.hyperllm.domain.model.enums.BillingMode;
import app.pagefault.hyperllm.domain.model.enums.ModelCapability;
import app.pagefault.hyperllm.domain.model.enums.ModelStatus;
import app.pagefault.hyperllm.domain.model.enums.ModelVisibility;
import app.pagefault.hyperllm.domain.model.enums.ProtocolFamily;
import app.pagefault.hyperllm.domain.model.enums.RoutingStrategy;
import app.pagefault.hyperllm.domain.model.routing.GatewayChannelCandidate;
import app.pagefault.hyperllm.domain.model.routing.GatewayModelRoute;
import app.pagefault.hyperllm.domain.model.routing.PricingRule;
import app.pagefault.hyperllm.domain.model.routing.VirtualModel;
import app.pagefault.hyperllm.gateway.cache.GatewayConfigCacheService;
import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import app.pagefault.hyperllm.gateway.outbox.OutboxAdmissionGate;
import app.pagefault.hyperllm.gateway.outbox.OutboxQueue;
import app.pagefault.hyperllm.gateway.outbox.PendingMoneyEvent;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.ProviderCapabilities;
import app.pagefault.hyperllm.protocol.ir.TranslationPolicy;
import app.pagefault.hyperllm.protocol.ir.UpstreamEncodingContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayHotPathOptimizationsTest {

    @Test
    void parsedRouteMetadataPreParsesPolicyThinkingStyleHeadersAndCapabilities() {
        ObjectMapper mapper = new ObjectMapper();
        UUID modelId = UUID.randomUUID();
        UUID chOpenAi = UUID.randomUUID();
        UUID chDeepSeek = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        VirtualModel vm = new VirtualModel(
                modelId, "reasoner-x", "Reasoner X", "Reasoner", ModelCapability.CHAT, ProtocolFamily.OPENAI,
                128_000, 8192, true, true, true, false, RoutingStrategy.PRIORITY_WEIGHTED,
                false, 0, ModelVisibility.PUBLIC, null, ModelStatus.ACTIVE,
                "{\"thinkingStyle\":\"INTERLEAVED_REASONING\",\"toolCallIdMaxLength\":48}",
                true, "STRICT", null, 1L, now, now
        );
        PricingRule pricing = new PricingRule(
                UUID.randomUUID(), modelId, BillingMode.TOKEN,
                1_000_000L, 2_000_000L, 0L, 0L, 0L, 0L,
                null, null, now.minusDays(1), null, null, now
        );
        GatewayChannelCandidate c1 = new GatewayChannelCandidate(
                UUID.randomUUID(), chOpenAi, "oai", "us-east", "OPENAI", ProtocolFamily.OPENAI,
                List.of("OPENAI_CHAT"), "OPENAI_CHAT", "OPENAI_CHAT", AuthScheme.BEARER,
                "https://api.openai.com/v1", "o3-mini", (short) 1, 100, 10_000,
                new byte[0], (short) 1,
                "{\"X-Org-Tier\":\"gold\",\"ignoredNumeric\":42,\"ignoredNull\":null}", null, null, null
        );
        GatewayChannelCandidate c2 = new GatewayChannelCandidate(
                UUID.randomUUID(), chDeepSeek, "ds", "ap-east", "DEEPSEEK", ProtocolFamily.OPENAI,
                List.of("OPENAI_CHAT"), "OPENAI_CHAT", "OPENAI_CHAT", AuthScheme.BEARER,
                "https://api.deepseek.com/v1", "deepseek-r1", (short) 2, 100, 10_000,
                new byte[0], (short) 1, "{}", null, null, null
        );

        GatewayModelRoute route = new GatewayModelRoute(vm, pricing, List.of(c1, c2));
        GatewayConfigCacheService.ParsedRouteMetadata parsed =
                GatewayConfigCacheService.ParsedRouteMetadata.parse(route, mapper);

        assertThat(parsed.translationPolicy()).isEqualTo(TranslationPolicy.STRICT);
        assertThat(parsed.thinkingStyle()).isEqualTo("INTERLEAVED_REASONING");
        assertThat(parsed.extraHeadersFor(chOpenAi)).containsExactlyEntriesOf(java.util.Map.of("X-Org-Tier", "gold"));
        assertThat(parsed.extraHeadersFor(chDeepSeek)).isEmpty();

        ProviderCapabilities dsChatCaps = parsed.capabilitiesFor(chDeepSeek, ProtocolKind.OPENAI_CHAT);
        assertThat(dsChatCaps).isNotNull();
        assertThat(dsChatCaps.supportsThinking()).isTrue();
        assertThat(dsChatCaps.interleavedReasoningField()).isEqualTo("reasoning");
        assertThat(dsChatCaps.toolCallIdMaxLength()).isEqualTo(48);

        // UpstreamEncodingContext returns the pre-resolved instance directly without re-parsing metadataJson.
        UpstreamEncodingContext ctx = new UpstreamEncodingContext(
                c2.upstreamModelName(), vm.maxOutputTokens(), parsed.translationPolicy(), parsed.thinkingStyle(),
                c2.providerCode(), vm.supportsThinking(), "NOT_EVEN_VALID_JSON", dsChatCaps
        );
        assertThat(ctx.capabilitiesFor(ProtocolKind.OPENAI_CHAT)).isSameAs(dsChatCaps);
    }

    @Test
    void streamingCredentialParserExtractsApiKeyWithoutDom() throws Exception {
        assertThat(GatewayProxyService.extractApiKeyFromJson("{\"apiKey\":\"sk-live-123\"}")).isEqualTo("sk-live-123");
        assertThat(GatewayProxyService.extractApiKeyFromJson(
                "{\"nested\":{\"apiKey\":\"wrong\"},\"tags\":[\"a\",\"b\"],\"apiKey\":\"sk-real-\\\"quoted\\\"\",\"extra\":true}"))
                .isEqualTo("sk-real-\"quoted\"");
        assertThat(GatewayProxyService.extractApiKeyFromJson("{\"apiKey\":null}")).isEmpty();
        assertThat(GatewayProxyService.extractApiKeyFromJson("{\"other\":\"value\"}")).isEmpty();
        assertThat(GatewayProxyService.extractApiKeyFromJson("")).isEmpty();
        assertThat(GatewayProxyService.extractApiKeyFromJson(null)).isEmpty();
        assertThatThrownBy(() -> GatewayProxyService.extractApiKeyFromJson("{malformed"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void lockFreeOutboxQueueHandlesConcurrentOfferDrainAndAdmissionChecks() throws Exception {
        GatewayProperties props = new GatewayProperties();
        props.getOutbox().setQueueCapacity(256);
        props.getOutbox().setAdmissionPauseRatio(0.5);
        OutboxQueue queue = new OutboxQueue(props);
        OutboxAdmissionGate gate = new OutboxAdmissionGate(queue);

        PendingMoneyEvent sample = new PendingMoneyEvent(
                UUID.randomUUID(), "org-1", "CONSUME", "{}", OffsetDateTime.now(ZoneOffset.UTC));

        int producers = 4;
        int eventsPerProducer = 500;
        ExecutorService pool = Executors.newFixedThreadPool(producers + 2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch producersDone = new CountDownLatch(producers);
        List<Future<?>> futures = new java.util.ArrayList<>();
        try {
            for (int p = 0; p < producers; p++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        for (int i = 0; i < eventsPerProducer; i++) {
                            queue.offer(sample);
                            try {
                                gate.check();
                            } catch (Exception ignored) {
                                // transient full queue refusal is expected under burst
                            }
                        }
                    } finally {
                        producersDone.countDown();
                    }
                    return null;
                }));
            }
            futures.add(pool.submit(() -> {
                start.await();
                while (producersDone.getCount() > 0 || queue.depth() > 0) {
                    queue.takeBatch(64, 5);
                }
                return null;
            }));
            start.countDown();
            for (Future<?> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(queue.depth()).isZero();
        // After full drain, admission must immediately recover.
        gate.check();
    }
}

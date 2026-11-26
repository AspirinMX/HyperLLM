package app.pagefault.hyperllm.gateway.proxy;

import app.pagefault.hyperllm.common.crypto.AesGcmCipher;
import app.pagefault.hyperllm.common.crypto.ApiKeyGenerator;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.money.CostMath;
import app.pagefault.hyperllm.domain.billing.AllocationDetailCodec;
import app.pagefault.hyperllm.domain.billing.AllocationLeg;
import app.pagefault.hyperllm.domain.billing.AllocationPlan;
import app.pagefault.hyperllm.domain.billing.BillableUsage;
import app.pagefault.hyperllm.domain.billing.EntitlementAllocator;
import app.pagefault.hyperllm.domain.billing.EntitlementSnapshot;
import app.pagefault.hyperllm.domain.pricing.PricingResolver;
import app.pagefault.hyperllm.domain.pricing.ResolvedPricing;
import app.pagefault.hyperllm.common.messaging.LlmRequestEvent;
import app.pagefault.hyperllm.common.net.CidrMatcher;
import app.pagefault.hyperllm.domain.model.enums.ApiKeyStatus;
import app.pagefault.hyperllm.domain.model.enums.ModelStatus;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import app.pagefault.hyperllm.domain.model.enums.ProjectStatus;
import app.pagefault.hyperllm.domain.model.routing.GatewayChannelCandidate;
import app.pagefault.hyperllm.domain.model.routing.GatewayModelRoute;
import app.pagefault.hyperllm.domain.model.routing.PricingRule;
import app.pagefault.hyperllm.domain.model.routing.VirtualModel;
import app.pagefault.hyperllm.domain.model.tenant.GatewayKeyContext;
import app.pagefault.hyperllm.gateway.billing.BillingAdmissionException;
import app.pagefault.hyperllm.gateway.billing.HeldLegCharger;
import app.pagefault.hyperllm.gateway.billing.ChannelCapacityService;
import app.pagefault.hyperllm.gateway.billing.LeaseHeartbeatRegistry;
import app.pagefault.hyperllm.gateway.billing.ProjectBudgetService;
import app.pagefault.hyperllm.gateway.billing.PreHoldService;
import app.pagefault.hyperllm.gateway.billing.GatewayTelemetryService;
import app.pagefault.hyperllm.gateway.cache.GatewayConfigCacheService;
import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import app.pagefault.hyperllm.gateway.outbox.OutboxAdmissionGate;
import app.pagefault.hyperllm.gateway.routing.ChannelSelector;
import app.pagefault.hyperllm.gateway.routing.UpstreamWebClientPool;
import app.pagefault.hyperllm.protocol.codec.CodecSupport;
import app.pagefault.hyperllm.protocol.io.ByteSink;
import app.pagefault.hyperllm.protocol.ir.CanonicalRequest;
import app.pagefault.hyperllm.protocol.ir.InboundRequestContext;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.StreamRequestMetadata;
import app.pagefault.hyperllm.protocol.ir.TokenUsage;
import app.pagefault.hyperllm.protocol.ir.TranslationPolicy;
import app.pagefault.hyperllm.protocol.ir.UpstreamEncodingContext;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import app.pagefault.hyperllm.protocol.translate.ProtocolTranslator;
import app.pagefault.hyperllm.protocol.translate.StreamBridge;
import app.pagefault.hyperllm.protocol.translate.UpstreamEndpointResolver;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.reactivestreams.Subscription;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.util.concurrent.Queues;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class GatewayProxyService {

    private record CallerContext(
            UUID orgId,
            UUID projectId,
            UUID apiKeyId,
            UUID userId,
            List<String> keyAllowedModels,
            List<String> projectAllowedModels,
            Integer keyRpmLimit,
            Integer projRpmLimit,
            Integer keyTpmLimit,
            Integer projTpmLimit,
            Integer keyMaxConcurrency,
            Integer projMaxConcurrency,
            int orgMaxConcurrency,
            Integer orgDiscountRatioBps,
            int tierDefaultDiscountBps,
            Long projectMonthlyHardCapMicros,
            Long projectMonthlySoftCapMicros
    ) {
    }

    private record AdmitResult(String status, ProjectBudgetService.BudgetContext budget) {
    }

    /**
     * Lightweight request metadata extracted immediately after parsing so downstream Reactive
     * closures never capture raw {@code byte[]} bodies or bare {@link CanonicalRequest} graphs.
     */
    private record DecodedRequestSummary(
            RequestIrHolder irHolder,
            String model,
            boolean stream,
            Integer maxOutputTokens,
            StreamRequestMetadata streamMeta,
            int estimatedPromptTokens,
            int estimatedEncodedBytes,
            int rawBodyBytes
    ) {
    }

    /** Direct {@link DataBuffer} holder enabling deterministic discard cleanup on async offload cancellation. */
    private record EncodedUpstreamBuffer(DataBuffer buffer, int contentLength) {
        void release() {
            DataBufferUtils.release(buffer);
        }
    }

    private static final Runnable NOOP_RUNNABLE = () -> {};
    private static final int DEFAULT_MAX_IN_MEMORY_BYTES = 16 * 1024 * 1024;
    private static final JsonFactory CREDENTIAL_JSON_FACTORY = new JsonFactory();

    /** All healthy candidates were tried and none had channel capacity left. */
    private static final class RetryableCapacityException extends RuntimeException {
    }

    private static final class RetryableUpstreamException extends RuntimeException {
        private final int statusCode;

        private RetryableUpstreamException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    private final GatewayConfigCacheService cacheService;
    private final OutboxAdmissionGate outboxAdmissionGate;
    private final PreHoldService preHoldService;
    private final LeaseHeartbeatRegistry leaseHeartbeatRegistry;
    private final ProjectBudgetService projectBudgetService;
    private final ChannelCapacityService channelCapacity;
    private final GatewayTelemetryService telemetryService;
    private static final long COALESCE_WINDOW_MS = Long.getLong("gateway.stream.coalesce-window-ms", 0L);
    private static final SseFrameCoalescer FRAME_COALESCER = new SseFrameCoalescer(
            Math.max(0L, COALESCE_WINDOW_MS) * 1_000_000L,
            Math.max(1L, Long.getLong("gateway.stream.coalesce-tick-ms", 5L)),
            64 * 1024,
            256);
    private static final boolean FRAME_COALESCER_ENABLED = COALESCE_WINDOW_MS > 0L;

    private final GatewayProperties properties;
    private final ProtocolTranslator protocolTranslator;
    private final AesGcmCipher aesGcmCipher;
    private final ChannelSelector channelSelector;
    private final UpstreamWebClientPool webClientPool;
    private final app.pagefault.hyperllm.gateway.cache.EntitlementMirrorService mirrorService;
    private final ObjectMapper objectMapper;
    private final TokenEstimator tokenEstimator;
    private final Scheduler protocolTranslationScheduler;
    private final int maxInMemoryBytes;
    private static final ByteBufAllocator DIRECT_BUFFER_ALLOCATOR = PooledByteBufAllocator.DEFAULT;
    private static final NettyDataBufferFactory NETTY_DATA_BUFFER_FACTORY =
            new NettyDataBufferFactory(DIRECT_BUFFER_ALLOCATOR);

    private final AtomicLong nextLazyReclaimNanos = new AtomicLong(0L);

    @Autowired
    public GatewayProxyService(
            GatewayConfigCacheService cacheService,
            OutboxAdmissionGate outboxAdmissionGate,
            PreHoldService preHoldService,
            LeaseHeartbeatRegistry leaseHeartbeatRegistry,
            ProjectBudgetService projectBudgetService,
            ChannelCapacityService channelCapacity,
            GatewayTelemetryService telemetryService,
            GatewayProperties properties,
            ProtocolTranslator protocolTranslator,
            AesGcmCipher aesGcmCipher,
            ChannelSelector channelSelector,
            UpstreamWebClientPool webClientPool,
            app.pagefault.hyperllm.gateway.cache.EntitlementMirrorService mirrorService,
            ObjectMapper objectMapper,
            TokenEstimator tokenEstimator,
            @Qualifier("protocolTranslationScheduler") Scheduler protocolTranslationScheduler,
            @Value("${spring.codec.max-in-memory-size:16MB}") DataSize maxInMemorySize
    ) {
        this.cacheService = cacheService;
        this.outboxAdmissionGate = outboxAdmissionGate;
        this.preHoldService = preHoldService;
        this.leaseHeartbeatRegistry = leaseHeartbeatRegistry;
        this.projectBudgetService = projectBudgetService;
        this.channelCapacity = channelCapacity;
        this.telemetryService = telemetryService;
        this.properties = properties;
        this.protocolTranslator = protocolTranslator;
        this.aesGcmCipher = aesGcmCipher;
        this.channelSelector = channelSelector;
        this.webClientPool = webClientPool;
        this.mirrorService = mirrorService;
        this.objectMapper = objectMapper;
        this.tokenEstimator = tokenEstimator;
        this.protocolTranslationScheduler = protocolTranslationScheduler;
        long bytes = maxInMemorySize != null ? maxInMemorySize.toBytes() : DEFAULT_MAX_IN_MEMORY_BYTES;
        this.maxInMemoryBytes = (int) Math.min(Integer.MAX_VALUE, Math.max(1024L, bytes));
    }

    public GatewayProxyService(
            GatewayConfigCacheService cacheService,
            OutboxAdmissionGate outboxAdmissionGate,
            PreHoldService preHoldService,
            ProjectBudgetService projectBudgetService,
            ChannelCapacityService channelCapacity,
            GatewayTelemetryService telemetryService,
            GatewayProperties properties,
            ProtocolTranslator protocolTranslator,
            AesGcmCipher aesGcmCipher,
            ChannelSelector channelSelector,
            UpstreamWebClientPool webClientPool,
            app.pagefault.hyperllm.gateway.cache.EntitlementMirrorService mirrorService,
            ObjectMapper objectMapper,
            TokenEstimator tokenEstimator,
            Scheduler protocolTranslationScheduler,
            DataSize maxInMemorySize
    ) {
        this(cacheService, outboxAdmissionGate, preHoldService, null, projectBudgetService, channelCapacity,
                telemetryService, properties, protocolTranslator, aesGcmCipher, channelSelector, webClientPool,
                mirrorService, objectMapper, tokenEstimator, protocolTranslationScheduler, maxInMemorySize);
    }

    public GatewayProxyService(
            GatewayConfigCacheService cacheService,
            OutboxAdmissionGate outboxAdmissionGate,
            PreHoldService preHoldService,
            ProjectBudgetService projectBudgetService,
            ChannelCapacityService channelCapacity,
            GatewayTelemetryService telemetryService,
            GatewayProperties properties,
            ProtocolTranslator protocolTranslator,
            AesGcmCipher aesGcmCipher,
            ChannelSelector channelSelector,
            UpstreamWebClientPool webClientPool,
            app.pagefault.hyperllm.gateway.cache.EntitlementMirrorService mirrorService,
            ObjectMapper objectMapper,
            TokenEstimator tokenEstimator,
            Scheduler protocolTranslationScheduler
    ) {
        this(cacheService, outboxAdmissionGate, preHoldService, projectBudgetService, channelCapacity,
                telemetryService, properties, protocolTranslator, aesGcmCipher, channelSelector, webClientPool,
                mirrorService, objectMapper, tokenEstimator, protocolTranslationScheduler,
                DataSize.ofBytes(DEFAULT_MAX_IN_MEMORY_BYTES));
    }

    public Mono<Map<String, Object>> listModels(ServerWebExchange exchange) {
        String rawToken = extractToken(exchange.getRequest(), ProtocolKind.OPENAI_CHAT);
        String clientIp = extractClientIp(exchange.getRequest());
        return authenticateCaller(rawToken, clientIp)
                .flatMap(caller -> cacheService.listPublicModels()
                        .map(models -> {
                            List<Map<String, Object>> visible = models.stream()
                                    .filter(m -> isModelAllowed(caller, m.modelCode()))
                                    .map(m -> Map.<String, Object>of(
                                            "id", m.modelCode(),
                                            "object", "model",
                                            "created", m.createdAt() != null ? m.createdAt().toEpochSecond() : 0L,
                                            "owned_by", m.providerBadge() != null ? m.providerBadge() : "hyperllm",
                                            "context_window", m.contextWindow()
                                    ))
                                    .toList();
                            return Map.of("object", "list", "data", visible);
                        }));
    }

    public Mono<Map<String, Object>> countAnthropicTokens(
            ServerWebExchange exchange,
            InboundRequestContext inboundContext
    ) {
        String rawToken = extractToken(exchange.getRequest(), ProtocolKind.ANTHROPIC_MESSAGES);
        String clientIp = extractClientIp(exchange.getRequest());
        return authenticateCaller(rawToken, clientIp)
                .flatMap(caller -> readAndParseRequest(exchange, ProtocolKind.ANTHROPIC_MESSAGES, inboundContext)
                        .flatMap(decoded -> {
                            decoded.irHolder().release();
                            return resolveCountTokens(caller, decoded.model(), decoded.estimatedPromptTokens());
                        }));
    }

    public Mono<Map<String, Object>> countAnthropicTokens(
            ServerWebExchange exchange,
            InboundRequestContext inboundContext,
            byte[] rawBody
    ) {
        String rawToken = extractToken(exchange.getRequest(), ProtocolKind.ANTHROPIC_MESSAGES);
        String clientIp = extractClientIp(exchange.getRequest());
        return authenticateCaller(rawToken, clientIp)
                .flatMap(caller -> {
                    DecodedRequestSummary decoded = parseAndSummarize(
                            ProtocolKind.ANTHROPIC_MESSAGES, rawBody, inboundContext);
                    decoded.irHolder().release();
                    return resolveCountTokens(caller, decoded.model(), decoded.estimatedPromptTokens());
                });
    }

    private Mono<Map<String, Object>> resolveCountTokens(CallerContext caller, String model, int estimatedPromptTokens) {
        if (!isModelAllowed(caller, model)) {
            return Mono.error(new HyperLlmException(ErrorCode.MODEL_NOT_ALLOWED));
        }
        return cacheService.resolveModelRoute(model)
                .switchIfEmpty(Mono.error(() -> new HyperLlmException(ErrorCode.MODEL_NOT_FOUND)))
                .flatMap(route -> {
                    if (route.model().status() != ModelStatus.ACTIVE) {
                        return Mono.error(new HyperLlmException(ErrorCode.MODEL_NOT_FOUND));
                    }
                    return Mono.just(Map.<String, Object>of("input_tokens", estimatedPromptTokens));
                });
    }

    public Mono<ResponseEntity<Flux<DataBuffer>>> proxyChatOrMessages(
            ServerWebExchange exchange,
            ProtocolKind protocolKind,
            byte[] rawBody
    ) {
        return proxy(exchange, protocolKind, InboundRequestContext.empty(), rawBody);
    }

    public Mono<ResponseEntity<Flux<DataBuffer>>> proxy(
            ServerWebExchange exchange,
            ProtocolKind protocolKind,
            InboundRequestContext inboundContext
    ) {
        long requestStartNanos = System.nanoTime();
        String requestId = UuidV7.next().toString();
        String traceId = requestId.replace("-", "");
        String rawToken = extractToken(exchange.getRequest(), protocolKind);
        String clientIp = extractClientIp(exchange.getRequest());

        // Detach the upward Subscription as soon as response headers emit so DispatcherHandler's
        // MonoFlatMap$FlatMapInner never pins the pre-TTFT pipeline across a long-lived SSE stream.
        return DetachUpstreamOperator.detach(authenticateCaller(rawToken, clientIp)
                .flatMap(caller -> readAndParseRequest(exchange, protocolKind, inboundContext)
                        .flatMap(decoded -> routeAndDispatch(
                                exchange, caller, decoded, protocolKind, inboundContext,
                                requestStartNanos, requestId, traceId, clientIp))));
    }

    public Mono<ResponseEntity<Flux<DataBuffer>>> proxy(
            ServerWebExchange exchange,
            ProtocolKind protocolKind,
            InboundRequestContext inboundContext,
            byte[] rawBody
    ) {
        long requestStartNanos = System.nanoTime();
        String requestId = UuidV7.next().toString();
        String traceId = requestId.replace("-", "");
        String rawToken = extractToken(exchange.getRequest(), protocolKind);
        String clientIp = extractClientIp(exchange.getRequest());

        AtomicReference<byte[]> bodySlot = new AtomicReference<>(rawBody);
        return DetachUpstreamOperator.detach(authenticateCaller(rawToken, clientIp)
                .flatMap(caller -> {
                    byte[] raw = bodySlot.getAndSet(null);
                    DecodedRequestSummary decoded = parseAndSummarize(protocolKind, raw, inboundContext);
                    return routeAndDispatch(
                            exchange, caller, decoded, protocolKind, inboundContext,
                            requestStartNanos, requestId, traceId, clientIp);
                })
                .doFinally(sig -> bodySlot.lazySet(null)));
    }

    private Mono<DecodedRequestSummary> readAndParseRequest(
            ServerWebExchange exchange,
            ProtocolKind protocolKind,
            InboundRequestContext inboundContext
    ) {
        int offloadThreshold = properties.getUpstream().getTranslationOffloadThresholdBytes();
        // Detach immediately after DataBufferUtils.join completes so Netty's long-lived
        // HttpServerOperations -> FluxReceive cannot pin the downstream flatMap/Callable chain
        // (and RequestByteArena) for the duration of a streaming response.
        return DetachUpstreamOperator.detach(
                        DataBufferUtils.join(exchange.getRequest().getBody(), maxInMemoryBytes))
                .onErrorMap(DataBufferLimitException.class, ex -> new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE, "Request body exceeds maximum allowed size", ex))
                .flatMap(dataBuffer -> {
                    RequestByteArena arena;
                    try {
                        arena = RequestByteArena.readFrom(dataBuffer);
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                    boolean offload = offloadThreshold >= 0 && arena.size() >= offloadThreshold;
                    if (!offload) {
                        return Mono.just(parseAndSummarize(protocolKind, arena, inboundContext));
                    }
                    return Mono.fromCallable(() -> parseAndSummarize(protocolKind, arena, inboundContext))
                            .subscribeOn(protocolTranslationScheduler)
                            .doFinally(sig -> arena.clear());
                })
                .switchIfEmpty(Mono.fromSupplier(() ->
                        parseAndSummarize(protocolKind, RequestByteArena.EMPTY, inboundContext)));
    }

    private DecodedRequestSummary parseAndSummarize(
            ProtocolKind protocolKind,
            byte[] rawBody,
            InboundRequestContext inboundContext
    ) {
        return parseAndSummarize(protocolKind, RequestByteArena.wrapContiguous(rawBody), inboundContext);
    }

    private DecodedRequestSummary parseAndSummarize(
            ProtocolKind protocolKind,
            RequestByteArena arena,
            InboundRequestContext inboundContext
    ) {
        // Single authoritative parse of the downstream body: every later decision
        // (routing, admission, billing, outbound bytes) derives from this IR graph.
        int rawBytes = arena.size();
        CanonicalRequest request;
        try {
            request = arena.parse(protocolTranslator, protocolKind, inboundContext);
        } finally {
            arena.clear();
        }
        int estimatedPromptTokens = tokenEstimator.estimatePromptTokens(request);
        int estimatedEncodedBytes = CodecSupport.estimateEncodedCapacity(request);
        StreamRequestMetadata streamMeta = StreamRequestMetadata.fromCanonical(request, estimatedPromptTokens);
        return new DecodedRequestSummary(
                new RequestIrHolder(request),
                request.model(),
                request.stream(),
                request.maxOutputTokens(),
                streamMeta,
                estimatedPromptTokens,
                estimatedEncodedBytes,
                rawBytes
        );
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> routeAndDispatch(
            ServerWebExchange exchange,
            CallerContext caller,
            DecodedRequestSummary decoded,
            ProtocolKind protocolKind,
            InboundRequestContext inboundContext,
            long requestStartNanos,
            String requestId,
            String traceId,
            String clientIp
    ) {
        if (!isModelAllowed(caller, decoded.model())) {
            decoded.irHolder().release();
            return Mono.error(new HyperLlmException(ErrorCode.MODEL_NOT_ALLOWED));
        }
        return cacheService.resolveModelRoute(decoded.model())
                .switchIfEmpty(Mono.error(() -> new HyperLlmException(ErrorCode.MODEL_NOT_FOUND)))
                .flatMap(route -> {
                    VirtualModel vm = route.model();
                    if (vm.status() != ModelStatus.ACTIVE) {
                        return Mono.error(new HyperLlmException(ErrorCode.MODEL_NOT_FOUND));
                    }
                    if (route.activePricing() == null) {
                        return Mono.error(new HyperLlmException(
                                ErrorCode.MODEL_PRICING_MISSING,
                                "Model " + vm.modelCode() + " has no active pricing rule"));
                    }
                    List<GatewayChannelCandidate> candidates = vm.allowProtocolTranslation()
                            ? route.candidates()
                            : route.candidates().stream()
                                    .filter(c -> c.isHomomorphicFor(protocolKind.name()))
                                    .toList();
                    if (candidates.isEmpty()) {
                        return Mono.error(new HyperLlmException(
                                ErrorCode.CHANNEL_UNAVAILABLE,
                                "No active channel available for protocol " + protocolKind
                        ));
                    }
                    return preparePreHold(caller, route, decoded.maxOutputTokens(), decoded.estimatedPromptTokens(), requestId)
                            .flatMap(lease -> dispatchWithRetry(
                                    exchange, decoded, protocolKind, inboundContext,
                                    caller, route, candidates, lease, requestStartNanos, requestId, traceId, clientIp
                            ).onErrorResume(ex -> releaseHold(lease).then(Mono.error(unwrapRetryError(ex)))));
                })
                .doOnError(ex -> decoded.irHolder().release())
                .doOnCancel(decoded.irHolder()::release);
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> dispatchWithRetry(
            ServerWebExchange exchange,
            DecodedRequestSummary decoded,
            ProtocolKind protocolKind,
            InboundRequestContext inboundContext,
            CallerContext caller,
            GatewayModelRoute route,
            List<GatewayChannelCandidate> candidates,
            PreHoldService.Lease lease,
            long requestStartNanos,
            String requestId,
            String traceId,
            String clientIp
    ) {
        Set<UUID> excludedChannels = ConcurrentHashMap.newKeySet();
        AtomicInteger attemptCounter = new AtomicInteger(0);
        GatewayConfigCacheService.ParsedRouteMetadata parsedMeta = resolveParsedMetadata(route);
        TranslationPolicy policy = parsedMeta.translationPolicy();
        String thinkingStyle = parsedMeta.thinkingStyle();
        StreamRequestMetadata streamMeta = decoded.streamMeta();
        boolean isStream = decoded.stream();
        RequestIrHolder irHolder = decoded.irHolder();
        String anthropicVersion = inboundContext.anthropicVersion();
        String anthropicBeta = inboundContext.anthropicBeta();
        int maxRetryAttempts = properties.getUpstream().getMaxRetryAttempts();

        int offloadThreshold = properties.getUpstream().getTranslationOffloadThresholdBytes();
        boolean offloadTranslation = offloadThreshold >= 0 && decoded.rawBodyBytes() >= offloadThreshold;

        return Mono.defer(() -> {
            GatewayChannelCandidate candidate = channelSelector.selectNext(candidates, excludedChannels, protocolKind);
            if (candidate == null) {
                return Mono.error(new HyperLlmException(ErrorCode.CHANNEL_UNAVAILABLE, "All upstream channels exhausted"));
            }
            int attemptIndex = attemptCounter.getAndIncrement();
            int retryCount = Math.max(0, attemptIndex - 1);
            String attemptId = lease.holdId() + ":" + attemptIndex;
            boolean hasFallback = attemptIndex < maxRetryAttempts
                    && hasAnotherEligibleCandidate(candidates, excludedChannels, candidate.channelId());

            ProtocolKind outboundProtocol = resolveOutboundProtocol(candidate, protocolKind);
            UpstreamEncodingContext encodingContext = new UpstreamEncodingContext(
                    candidate.upstreamModelName(),
                    route.model().maxOutputTokens(),
                    policy,
                    thinkingStyle,
                    candidate.providerCode(),
                    route.model().supportsThinking(),
                    route.model().metadataJson(),
                    parsedMeta.capabilitiesFor(candidate.channelId(), outboundProtocol)
            );
            Map<String, String> extraHeaders = parsedMeta.extraHeadersFor(candidate.channelId());
            String upstreamApiKey = decryptChannelApiKey(candidate);
            String targetUrl = UpstreamEndpointResolver.buildInferenceUrl(
                    candidate.resolvedBaseUrl(), outboundProtocol, candidate.upstreamModelName(), isStream);
            WebClient client = webClientPool.clientFor(candidate.providerCode());

            // Check channel capacity BEFORE encoding the upstream body so saturated channels
            // never pay for a 400 KB serialization and closures never capture the encoded buffer.
            return channelCapacity.acquire(candidate.channelId(), attemptId, candidate, lease.estimatedTokens())
                    .flatMap(capacity -> {
                        if (!"OK".equals(capacity)) {
                            excludedChannels.add(candidate.channelId());
                            return Mono.error(new RetryableCapacityException());
                        }
                        AtomicBoolean capacityReleased = new AtomicBoolean(false);
                        Runnable releaseCapacity = channelCapacity.hasConcurrencyLimit(candidate)
                                ? () -> {
                                    if (capacityReleased.compareAndSet(false, true)) {
                                        channelCapacity.release(candidate.channelId(), attemptId, candidate).subscribe();
                                    }
                                }
                                : NOOP_RUNNABLE;
                        Runnable releaseIrOnCommit = hasFallback ? irHolder::release : NOOP_RUNNABLE;

                        if (!offloadTranslation) {
                            EncodedUpstreamBuffer encoded;
                            try {
                                CanonicalRequest req = hasFallback ? irHolder.get() : irHolder.take();
                                if (req == null) {
                                    releaseCapacity.run();
                                    return Mono.error(new IllegalStateException(
                                            "Request IR already released before upstream encode"));
                                }
                                encoded = encodeToDirectBuffer(
                                        req, outboundProtocol, encodingContext, decoded.estimatedEncodedBytes());
                            } catch (Throwable ex) {
                                releaseCapacity.run();
                                return Mono.error(ex);
                            }
                            return executeSingleAttempt(
                                    exchange, client, targetUrl, candidate, extraHeaders, upstreamApiKey,
                                    streamMeta, isStream, anthropicVersion, anthropicBeta,
                                    encoded.buffer(), encoded.contentLength(),
                                    protocolKind, outboundProtocol,
                                    caller, route, lease, excludedChannels,
                                    requestStartNanos, requestId, traceId, clientIp, retryCount,
                                    releaseCapacity, releaseIrOnCommit
                            );
                        }

                        return Mono.fromCallable(() -> {
                                    CanonicalRequest req = hasFallback ? irHolder.get() : irHolder.take();
                                    if (req == null) {
                                        throw new IllegalStateException(
                                                "Request IR already released before upstream encode");
                                    }
                                    return encodeToDirectBuffer(
                                            req, outboundProtocol, encodingContext, decoded.estimatedEncodedBytes());
                                })
                                .subscribeOn(protocolTranslationScheduler)
                                .doOnDiscard(EncodedUpstreamBuffer.class, EncodedUpstreamBuffer::release)
                                .doOnError(ex -> releaseCapacity.run())
                                .doOnCancel(releaseCapacity)
                                .flatMap(encoded -> executeSingleAttempt(
                                        exchange, client, targetUrl, candidate, extraHeaders, upstreamApiKey,
                                        streamMeta, isStream, anthropicVersion, anthropicBeta,
                                        encoded.buffer(), encoded.contentLength(),
                                        protocolKind, outboundProtocol,
                                        caller, route, lease, excludedChannels,
                                        requestStartNanos, requestId, traceId, clientIp, retryCount,
                                        releaseCapacity, releaseIrOnCommit
                                ));
                    });
        }).retryWhen(Retry.max(maxRetryAttempts)
                .filter(ex -> ex instanceof RetryableUpstreamException
                        || ex instanceof RetryableCapacityException))
                .doFinally(sig -> irHolder.release());
    }

    private GatewayConfigCacheService.ParsedRouteMetadata resolveParsedMetadata(GatewayModelRoute route) {
        GatewayConfigCacheService.ParsedRouteMetadata cached = cacheService.resolveParsedMetadata(route);
        return cached != null ? cached : GatewayConfigCacheService.ParsedRouteMetadata.parse(route, objectMapper);
    }

    static boolean hasAnotherEligibleCandidate(
            List<GatewayChannelCandidate> candidates,
            Set<UUID> excludedChannels,
            UUID currentChannelId
    ) {
        for (GatewayChannelCandidate c : candidates) {
            UUID id = c.channelId();
            if (!id.equals(currentChannelId) && !excludedChannels.contains(id)) {
                return true;
            }
        }
        return false;
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> executeSingleAttempt(
            ServerWebExchange exchange,
            WebClient client,
            String targetUrl,
            GatewayChannelCandidate candidate,
            Map<String, String> extraHeaders,
            String upstreamApiKey,
            StreamRequestMetadata streamMeta,
            boolean isStream,
            String anthropicVersion,
            String anthropicBeta,
            DataBuffer initialBodyBuffer,
            long contentLength,
            ProtocolKind protocolKind,
            ProtocolKind outboundProtocol,
            CallerContext caller,
            GatewayModelRoute route,
            PreHoldService.Lease lease,
            Set<UUID> excludedChannels,
            long requestStartNanos,
            String requestId,
            String traceId,
            String clientIp,
            int retryCount,
            Runnable releaseCapacity,
            Runnable onRetryWindowClosed
    ) {
        Sinks.One<ResponseEntity<Flux<DataBuffer>>> responseSink = Sinks.one();
        AtomicReference<Subscription> upstreamSubRef = new AtomicReference<>();
        AtomicReference<DataBuffer> bodyRef = new AtomicReference<>(initialBodyBuffer);

        Flux<Void> upstreamExecution = client.post()
                .uri(targetUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(contentLength)
                .headers(headers -> applyUpstreamHeaders(headers, candidate, extraHeaders, outboundProtocol,
                        upstreamApiKey, anthropicVersion, anthropicBeta))
                .body(BodyInserters.fromDataBuffers(Mono.fromSupplier(() -> {
                    DataBuffer buf = bodyRef.getAndSet(null);
                    return buf != null ? buf : NETTY_DATA_BUFFER_FACTORY.wrap(Unpooled.EMPTY_BUFFER);
                }).doOnDiscard(DataBuffer.class, DataBufferUtils::release)))
                .exchangeToFlux(clientResponse -> {
                    int status = clientResponse.statusCode().value();
                    if (status == 429 || status >= 500) {
                        excludedChannels.add(candidate.channelId());
                        return clientResponse.releaseBody()
                                .thenMany(Flux.error(new RetryableUpstreamException(
                                        status, "Upstream returned retryable status " + status)));
                    }
                    // Retry window is now permanently closed (2xx or non-retryable 4xx headers received):
                    // release the decoded CanonicalRequest immediately before streaming or body buffering.
                    onRetryWindowClosed.run();
                    if (!isStream || !clientResponse.statusCode().is2xxSuccessful()) {
                        return clientResponse.bodyToMono(byte[].class)
                                .defaultIfEmpty(new byte[0])
                                .flatMapMany(bodyBytes -> handleNonStreamingResponse(
                                        exchange, streamMeta, protocolKind, outboundProtocol, status, bodyBytes,
                                        caller, route, candidate, lease, requestStartNanos,
                                        requestId, traceId, clientIp, retryCount
                                ).doFinally(signal -> releaseCapacity.run())
                                        .doOnNext(responseSink::tryEmitValue)
                                        .doOnError(responseSink::tryEmitError)
                                        .thenMany(Flux.empty()));
                    }
                    return bridgeStreamingResponse(
                            exchange, clientResponse, streamMeta, protocolKind, outboundProtocol,
                            caller, route, candidate, lease, requestStartNanos,
                            requestId, traceId, clientIp, retryCount,
                            responseSink, upstreamSubRef, releaseCapacity
                    );
                });

        upstreamExecution.doOnSubscribe(upstreamSubRef::set)
                .subscribe(
                        ignored -> {},
                        ex -> {
                            DataBuffer leftover = bodyRef.getAndSet(null);
                            if (leftover != null) {
                                DataBufferUtils.release(leftover);
                            }
                            Throwable mapped = ex;
                            if (!(mapped instanceof RetryableUpstreamException) && !(mapped instanceof HyperLlmException)) {
                                excludedChannels.add(candidate.channelId());
                                mapped = new RetryableUpstreamException(502, "Upstream transport failure: " + mapped.getMessage());
                            }
                            responseSink.tryEmitError(mapped);
                        }
                );

        return responseSink.asMono()
                .doOnError(ex -> {
                    DataBuffer leftover = bodyRef.getAndSet(null);
                    if (leftover != null) {
                        DataBufferUtils.release(leftover);
                    }
                    releaseCapacity.run();
                })
                .doOnCancel(() -> {
                    DataBuffer leftover = bodyRef.getAndSet(null);
                    if (leftover != null) {
                        DataBufferUtils.release(leftover);
                    }
                    releaseCapacity.run();
                    Subscription sub = upstreamSubRef.get();
                    if (sub != null) {
                        sub.cancel();
                    }
                });
    }

    private EncodedUpstreamBuffer encodeToDirectBuffer(
            CanonicalRequest req,
            ProtocolKind outboundProtocol,
            UpstreamEncodingContext encodingContext,
            int estimatedCapacity
    ) {
        NettyByteBufSink sink = new NettyByteBufSink(DIRECT_BUFFER_ALLOCATOR, estimatedCapacity);
        try {
            protocolTranslator.encodeUpstreamRequest(req, outboundProtocol, encodingContext, sink);
            ByteBuf buf = sink.finishAndTransfer();
            int len = buf.readableBytes();
            return new EncodedUpstreamBuffer(NETTY_DATA_BUFFER_FACTORY.wrap(buf), len);
        } catch (Throwable ex) {
            sink.release();
            throw ex;
        }
    }

    private static final int STREAM_FRAME_BUFFER_CAPACITY = 256;

    private Flux<Void> bridgeStreamingResponse(
            ServerWebExchange exchange,
            org.springframework.web.reactive.function.client.ClientResponse clientResponse,
            StreamRequestMetadata streamMeta,
            ProtocolKind protocolKind,
            ProtocolKind outboundProtocol,
            CallerContext caller,
            GatewayModelRoute route,
            GatewayChannelCandidate candidate,
            PreHoldService.Lease lease,
            long requestStartNanos,
            String requestId,
            String traceId,
            String clientIp,
            int retryCount,
            Sinks.One<ResponseEntity<Flux<DataBuffer>>> responseSink,
            AtomicReference<Subscription> upstreamSubRef,
            Runnable releaseCapacity
    ) {
        StreamBridge bridge = protocolTranslator.newStreamBridge(streamMeta, outboundProtocol);
        AtomicBoolean settled = new AtomicBoolean(false);
        AtomicLong firstByteNanos = new AtomicLong(0L);
        DataBufferFactory bufferFactory = exchange.getResponse().bufferFactory();
        Sinks.Many<DataBuffer> frameSink = Sinks.many().unicast()
                .onBackpressureBuffer(Queues.<DataBuffer>get(STREAM_FRAME_BUFFER_CAPACITY).get());
        SseFrameBatcher frameBatcher = FRAME_COALESCER_ENABLED ? FRAME_COALESCER.newBatcher() : null;
        // Reusable per-stream scratch buffer for copying off-heap upstream chunks into the parser
        // without allocating a fresh byte[] on all 400+ chunks.
        byte[][] readScratchHolder = new byte[1][];
        ByteSink.HeapByteSink coalesceScratch = FRAME_COALESCER_ENABLED ? new ByteSink.HeapByteSink(512) : null;

        Flux<DataBuffer> clientFrames = frameSink.asFlux()
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
                .doOnCancel(() -> {
                    Subscription sub = upstreamSubRef.get();
                    if (sub != null) {
                        sub.cancel();
                    }
                })
                .doFinally(signal -> {
                    releaseCapacity.run();
                    if (settled.get()) {
                        return;
                    }
                    TokenUsage fallback = bridge.finalizeUsage(false);
                    settleReactive(
                            settled, false, fallback,
                            requestStartNanos, firstByteNanos.get(),
                            requestId, traceId, caller, route, candidate,
                            candidate.upstreamModelName(),
                            protocolKind, true, 200, "STREAM_INTERRUPTED", retryCount, clientIp, lease
                    ).subscribe();
                });

        responseSink.tryEmitValue(ResponseEntity.status(clientResponse.statusCode())
                .contentType(MediaType.parseMediaType(bridge.contentType()))
                .body(clientFrames));

        Flux<DataBuffer> translatedChunks = clientResponse.bodyToFlux(DataBuffer.class)
                .<DataBuffer>handle((dataBuffer, sink) -> {
                    try {
                        int readable = dataBuffer.readableByteCount();
                        if (readable <= 0) {
                            return;
                        }
                        byte[] src;
                        int off;
                        if (dataBuffer instanceof NettyDataBuffer ndb && ndb.getNativeBuffer().hasArray()) {
                            ByteBuf nativeBuf = ndb.getNativeBuffer();
                            src = nativeBuf.array();
                            off = nativeBuf.arrayOffset() + nativeBuf.readerIndex();
                        } else {
                            byte[] scratch = readScratchHolder[0];
                            if (scratch == null || scratch.length < readable) {
                                scratch = new byte[Math.max(1024, readable)];
                                readScratchHolder[0] = scratch;
                            }
                            dataBuffer.read(scratch, 0, readable);
                            src = scratch;
                            off = 0;
                        }
                        if (FRAME_COALESCER_ENABLED) {
                            coalesceScratch.reset();
                            bridge.feed(src, off, readable, coalesceScratch);
                            if (coalesceScratch.writtenBytes() > 0) {
                                sink.next(bufferFactory.wrap(coalesceScratch.toCopiedByteArray()));
                            }
                        } else {
                            NettyByteBufSink outSink = new NettyByteBufSink(
                                    DIRECT_BUFFER_ALLOCATOR, Math.max(256, readable + 128));
                            try {
                                bridge.feed(src, off, readable, outSink);
                                if (outSink.writtenBytes() > 0) {
                                    sink.next(NETTY_DATA_BUFFER_FACTORY.wrap(outSink.finishAndTransfer()));
                                } else {
                                    outSink.release();
                                }
                            } catch (Throwable ex) {
                                outSink.release();
                                throw ex;
                            }
                        }
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                })
                .concatWith(Mono.defer(() -> {
                    if (FRAME_COALESCER_ENABLED) {
                        coalesceScratch.reset();
                        bridge.complete(coalesceScratch);
                        if (coalesceScratch.writtenBytes() > 0) {
                            return Mono.just(bufferFactory.wrap(coalesceScratch.toCopiedByteArray()));
                        }
                        return Mono.empty();
                    }
                    NettyByteBufSink outSink = new NettyByteBufSink(DIRECT_BUFFER_ALLOCATOR, 512);
                    try {
                        bridge.complete(outSink);
                        if (outSink.writtenBytes() > 0) {
                            return Mono.just(NETTY_DATA_BUFFER_FACTORY.wrap(outSink.finishAndTransfer()));
                        }
                        outSink.release();
                        return Mono.empty();
                    } catch (Throwable ex) {
                        outSink.release();
                        return Mono.error(ex);
                    }
                }))
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release);

        return Flux.using(() -> startHeartbeat(lease), heartbeat -> translatedChunks
                .switchOnFirst((firstSignal, frames) -> {
                    if (!firstSignal.hasValue()) {
                        return frames;
                    }
                    // Mark first-byte served ONCE on frame 1; frames 2..N bypass concatMap/Mono.just entirely.
                    return preHoldService.markServed(lease)
                            .onErrorResume(ex -> {
                                log.warn("Cannot mark first response frame for hold {}", lease.holdId(), ex);
                                return Mono.empty();
                            })
                            .thenMany(frames);
                })
                .doOnNext(frame -> {
                    firstByteNanos.compareAndSet(0L, System.nanoTime());
                    if (frameBatcher != null) {
                        byte[] bytes = new byte[frame.readableByteCount()];
                        try {
                            frame.read(bytes);
                        } finally {
                            DataBufferUtils.release(frame);
                        }
                        FRAME_COALESCER.offer(frameBatcher,
                                batch -> emitDownstreamBuffer(frameSink, bufferFactory.wrap(batch), upstreamSubRef),
                                bytes);
                    } else {
                        emitDownstreamBuffer(frameSink, frame, upstreamSubRef);
                    }
                })
                .then(Mono.defer(() -> settleReactive(
                        settled, true, bridge.finalizeUsage(true),
                        requestStartNanos, firstByteNanos.get(),
                        requestId, traceId, caller, route, candidate,
                        candidate.upstreamModelName(),
                        protocolKind, true, 200, null, retryCount, clientIp, lease
                )))
                .then(Mono.fromRunnable(() -> {
                    if (frameBatcher != null) {
                        FRAME_COALESCER.drain(frameBatcher,
                                batch -> emitDownstreamBuffer(frameSink, bufferFactory.wrap(batch), upstreamSubRef));
                    }
                    frameSink.tryEmitComplete();
                }))
                .onErrorResume(ex -> settleReactive(
                        settled, false, bridge.finalizeUsage(false),
                        requestStartNanos, firstByteNanos.get(),
                        requestId, traceId, caller, route, candidate,
                        candidate.upstreamModelName(),
                        protocolKind, true, 200, "STREAM_INTERRUPTED", retryCount, clientIp, lease
                ).then(Mono.fromRunnable(() -> frameSink.tryEmitError(ex))))
                .thenMany(Flux.<Void>empty()), Disposable::dispose);
    }

    private static void emitDownstreamBuffer(
            Sinks.Many<DataBuffer> frameSink,
            DataBuffer buffer,
            AtomicReference<Subscription> upstreamSubRef
    ) {
        Sinks.EmitResult result = frameSink.tryEmitNext(buffer);
        if (result.isFailure()) {
            DataBufferUtils.release(buffer);
            if (result == Sinks.EmitResult.FAIL_OVERFLOW || result == Sinks.EmitResult.FAIL_CANCELLED) {
                Subscription sub = upstreamSubRef.get();
                if (sub != null) {
                    sub.cancel();
                }
            }
        }
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> handleNonStreamingResponse(
            ServerWebExchange exchange,
            StreamRequestMetadata streamMeta,
            ProtocolKind protocolKind,
            ProtocolKind outboundProtocol,
            int status,
            byte[] bodyBytes,
            CallerContext caller,
            GatewayModelRoute route,
            GatewayChannelCandidate candidate,
            PreHoldService.Lease lease,
            long requestStartNanos,
            String requestId,
            String traceId,
            String clientIp,
            int retryCount
    ) {
        AtomicBoolean settled = new AtomicBoolean(false);
        TokenUsage usage = TokenUsage.empty();
        byte[] clientBody;
        if (status >= 200 && status < 300) {
            ProtocolTranslator.TranslatedResponse translated =
                    protocolTranslator.translateNonStreamingResponse(streamMeta, outboundProtocol, bodyBytes);
            usage = translated.usage();
            clientBody = translated.clientBody();
        } else {
            clientBody = protocolTranslator.translateUpstreamError(protocolKind, outboundProtocol, status, bodyBytes);
        }
        long nowNanos = System.nanoTime();
        String errorCode = status >= 400 ? "UPSTREAM_HTTP_" + status : null;
        return settleReactive(
                settled, status >= 200 && status < 300, usage,
                requestStartNanos, nowNanos,
                requestId, traceId, caller, route, candidate,
                candidate.upstreamModelName(), protocolKind, false, status, errorCode, retryCount, clientIp, lease
        ).thenReturn(ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Flux.just(exchange.getResponse().bufferFactory().wrap(clientBody))));
    }

    private Mono<Void> settleReactive(
            AtomicBoolean settled,
            boolean cleanSuccess,
            TokenUsage usage,
            long startNanos,
            long firstByteNanos,
            String requestId,
            String traceId,
            CallerContext caller,
            GatewayModelRoute route,
            GatewayChannelCandidate candidate,
            String upstreamModel,
            ProtocolKind protocolKind,
            boolean isStream,
            int httpStatus,
            String errorCode,
            int retryCount,
            String clientIp,
            PreHoldService.Lease lease
    ) {
        if (!settled.compareAndSet(false, true)) {
            return Mono.empty();
        }
        if ((!cleanSuccess && firstByteNanos == 0L) || httpStatus < 200 || httpStatus >= 300) {
            return releaseHold(lease);
        }
        PricingRule pricing = route.activePricing();
        if (pricing == null) {
            log.warn("Settling request {} without an active pricing rule; no charge recorded", requestId);
            return releaseHold(lease);
        }

        long endNanos = System.nanoTime();
        int durationMs = (int) Math.max(1L, (endNanos - startNanos) / 1_000_000L);
        int ttftMs = firstByteNanos > 0L ? (int) Math.max(1L, (firstByteNanos - startNanos) / 1_000_000L) : durationMs;
        double tpotMs = usage.completionTokens() > 0 ? ((double) Math.max(0, durationMs - ttftMs)) / usage.completionTokens() : 0.0;
        OffsetDateTime eventCreatedAt = OffsetDateTime.now(ZoneOffset.UTC);

        BillableUsage billable = new BillableUsage(
                usage.promptTokens(), usage.completionTokens(),
                usage.cachedReadTokens(), usage.cachedWriteTokens(), usage.reasoningTokens());
        long inputTokens = billable.promptTokens() + billable.cacheReadTokens() + billable.cacheWriteTokens();

        return cacheService.resolvePricingOverride(caller.orgId(), route.model().id())
                .map(override -> PricingResolver.resolve(
                        caller.orgDiscountRatioBps(), caller.tierDefaultDiscountBps(), pricing, override, inputTokens))
                .defaultIfEmpty(PricingResolver.resolve(
                        caller.orgDiscountRatioBps(), caller.tierDefaultDiscountBps(), pricing, null, inputTokens))
                .flatMap(resolved -> chargeAndSettle(
                        resolved, pricing, billable, usage, caller, route, candidate, upstreamModel,
                        protocolKind, isStream, httpStatus, errorCode, retryCount, clientIp,
                        requestId, traceId, eventCreatedAt, durationMs, ttftMs, tpotMs, lease))
                .onErrorResume(ex -> {
                    log.error("Billing settlement failed for request {}", requestId, ex);
                    return Mono.empty();
                });
    }

    private Mono<Void> chargeAndSettle(
            ResolvedPricing resolved,
            PricingRule pricing,
            BillableUsage billable,
            TokenUsage usage,
            CallerContext caller,
            GatewayModelRoute route,
            GatewayChannelCandidate candidate,
            String upstreamModel,
            ProtocolKind protocolKind,
            boolean isStream,
            int httpStatus,
            String errorCode,
            int retryCount,
            String clientIp,
            String requestId,
            String traceId,
            OffsetDateTime eventCreatedAt,
            int durationMs,
            int ttftMs,
            double tpotMs,
            PreHoldService.Lease lease
    ) {
        long rawCost = CostMath.rawCostMicros(
                billable.promptTokens(), billable.completionTokens(), billable.cacheReadTokens(),
                billable.cacheWriteTokens(), billable.reasoningTokens(),
                resolved.inputPriceMicros1m(), resolved.outputPriceMicros1m(),
                resolved.cacheReadPriceMicros1m(), resolved.cacheWritePriceMicros1m(),
                resolved.reasoningPriceMicros1m());
        long exactCostMicros = CostMath.discountedCostMicros(rawCost, resolved.discountBps());
        long upstreamCostMicros = CostMath.discountedCostMicros(
                CostMath.rawCostMicros(
                        billable.promptTokens(), billable.completionTokens(), billable.cacheReadTokens(),
                        billable.cacheWriteTokens(), billable.reasoningTokens(),
                        pricing.inputPriceMicros1m(), pricing.outputPriceMicros1m(),
                        pricing.cacheReadPriceMicros1m(), pricing.cacheWritePriceMicros1m(),
                        PricingResolver.effectiveReasoningPrice(
                                pricing.reasoningPriceMicros1m(), pricing.outputPriceMicros1m())),
                candidate.effectiveCostDiscountBps());

        Mono<List<AllocationLeg>> heldLegsMono = lease.heldLegs().isEmpty()
                ? mirrorService.heldLegs(lease.holdId())
                : Mono.just(lease.heldLegs());
        return heldLegsMono
                .map(legs -> HeldLegCharger.charge(legs, exactCostMicros).legs())
                .flatMap(charges -> preHoldService.settle(lease, charges,
                        AllocationDetailCodec.encodeContextV1(resolved, billable), eventCreatedAt,
                        actualTokens(billable), exactCostMicros))
                .doOnNext(deducted -> {
                    LlmRequestEvent reqEvent = new LlmRequestEvent(
                            eventCreatedAt,
                            requestId,
                            traceId,
                            caller.orgId(),
                            caller.projectId(),
                            caller.apiKeyId(),
                            caller.userId(),
                            route.model().modelCode(),
                            candidate.providerCode(),
                            candidate.channelId(),
                            upstreamModel,
                            protocolKind.name(),
                            isStream ? 1 : 0,
                            httpStatus,
                            errorCode == null ? "" : errorCode,
                            retryCount,
                            0,
                            "NONE",
                            0,
                            usage.promptTokens(),
                            usage.cachedReadTokens(),
                            usage.cachedWriteTokens(),
                            usage.completionTokens(),
                            usage.reasoningTokens(),
                            exactCostMicros,
                            deducted.cashCharged(),
                            deducted.bonusCharged(),
                            upstreamCostMicros,
                            0L,
                            ttftMs,
                            tpotMs,
                            durationMs,
                            maskClientIp(clientIp),
                            candidate.region() != null ? candidate.region() : "default"
                    );
                    telemetryService.emitRequestEvent(reqEvent);
                }).then();
    }

    private static long actualTokens(BillableUsage usage) {
        return usage.promptTokens() + usage.cacheReadTokens() + usage.cacheWriteTokens()
                + usage.completionTokens() + usage.reasoningTokens();
    }

    private Disposable startHeartbeat(PreHoldService.Lease lease) {
        var billing = properties.getBilling();
        long ttlSeconds = billing.getLeaseTtlSeconds();
        long periodSeconds = Math.max(1, Math.min(billing.getHeartbeatMinIntervalSeconds(), ttlSeconds / 3));
        if (leaseHeartbeatRegistry != null) {
            return leaseHeartbeatRegistry.register(lease, periodSeconds, ttlSeconds);
        }
        return Flux.interval(Duration.ofSeconds(periodSeconds))
                .concatMap(tick -> preHoldService.renew(lease, ttlSeconds).onErrorResume(ex -> {
                    log.warn("Cannot renew hold {}", lease.holdId(), ex);
                    return Mono.just(true); // retry on the next heartbeat
                }))
                .takeUntil(renewed -> !renewed)
                .subscribe();
    }

    /**
     * Reserves the estimated maximum and records a smaller prompt-only floor for crash recovery.
     */
    private Mono<PreHoldService.Lease> preparePreHold(
            CallerContext caller,
            GatewayModelRoute route,
            Integer requestedMaxOutputTokens,
            int estimatedPromptTokens,
            String requestId
    ) {
        outboxAdmissionGate.check();
        GatewayProperties.Billing billing = properties.getBilling();
        String modelCode = route.model().modelCode();
        long promptEstimate = Math.max(0L, estimatedPromptTokens);
        int requestedMaxTokens = requestedMaxOutputTokens != null ? requestedMaxOutputTokens : 0;
        int outputCap = requestedMaxTokens > 0 ? requestedMaxTokens : billing.getDefaultOutputTokens();

        return cacheService.resolvePricingOverride(caller.orgId(), route.model().id())
                .map(override -> PricingResolver.resolve(
                        caller.orgDiscountRatioBps(), caller.tierDefaultDiscountBps(),
                        route.activePricing(), override, promptEstimate))
                .defaultIfEmpty(PricingResolver.resolve(
                        caller.orgDiscountRatioBps(), caller.tierDefaultDiscountBps(),
                        route.activePricing(), null, promptEstimate))
                .flatMap(resolved -> {
                    long raw = CostMath.rawCostMicros(
                            promptEstimate, outputCap, 0L, 0L, 0L,
                            resolved.inputPriceMicros1m(), resolved.outputPriceMicros1m(),
                            resolved.cacheReadPriceMicros1m(), resolved.cacheWritePriceMicros1m(),
                            resolved.reasoningPriceMicros1m());
                    long estimate = CostMath.discountedCostMicros(raw, resolved.discountBps());
                    long target = CostMath.discountedCostMicros(estimate, billing.getSafetyFactorBps());
                    long billable = promptEstimate + outputCap;
                    long floorCost = CostMath.discountedCostMicros(CostMath.rawCostMicros(
                            promptEstimate, 0L, 0L, 0L, 0L,
                            resolved.inputPriceMicros1m(), 0L, 0L, 0L, 0L), resolved.discountBps());
                    return maybeLazyReclaim(billing)
                            .then(mirrorService.read(caller.orgId(), modelCode, true))
                            .flatMap(mirror -> admitWithEstimate(
                                    caller, modelCode, target, billable, mirror.snapshot(),
                                    requestId, promptEstimate, floorCost, resolved));
                });
    }

    private Mono<Void> maybeLazyReclaim(GatewayProperties.Billing billing) {
        int batch = billing.getLazyReclaimBatch();
        if (batch <= 0) {
            return Mono.empty();
        }
        long intervalMs = billing.getLazyReclaimMinIntervalMs();
        if (intervalMs > 0L) {
            long now = System.nanoTime();
            long next = nextLazyReclaimNanos.get();
            if (now < next || !nextLazyReclaimNanos.compareAndSet(next, now + TimeUnit.MILLISECONDS.toNanos(intervalMs))) {
                return Mono.empty();
            }
        }
        return preHoldService.reclaimExpired(batch)
                .onErrorResume(ex -> Mono.just(0))
                .then();
    }

    private Mono<PreHoldService.Lease> admitWithEstimate(
            CallerContext caller,
            String modelCode,
            long holdTarget,
            long billableTokens,
            EntitlementSnapshot snapshot,
            String requestId,
            long promptTokens,
            long floorCost,
            ResolvedPricing pricing
    ) {
        AllocationPlan plan = EntitlementAllocator.allocate(holdTarget, billableTokens, snapshot);
        if (plan.legs().isEmpty()) {
            return Mono.error(new HyperLlmException(ErrorCode.BILLING_INSUFFICIENT_BALANCE));
        }

        var floor = HeldLegCharger.promptFloor(plan.legs(), snapshot, floorCost, promptTokens);
        String holdId = UuidV7.next().toString();
        List<String> concurrencyKeys = concurrencyKeys(caller);
        return projectBudgetService.ensure(caller.orgId(), caller.projectId(),
                        caller.projectMonthlyHardCapMicros(), caller.projectMonthlySoftCapMicros())
                .flatMap(budget -> admitOnce(caller, modelCode, holdTarget, billableTokens, requestId,
                        promptTokens, floorCost, pricing, plan, floor, holdId, budget)
                        .flatMap(result -> "MISS_BUDGET".equals(result.status())
                                ? projectBudgetService.reinstall(caller.orgId(), caller.projectId(), result.budget())
                                        .flatMap(refreshed -> admitOnce(caller, modelCode, holdTarget, billableTokens,
                                                requestId, promptTokens, floorCost, pricing, plan, floor, holdId,
                                                refreshed))
                                : Mono.just(result)))
                .flatMap(result -> switch (result.status()) {
                    case "OK" -> Mono.just(new PreHoldService.Lease(
                            caller.orgId(), concurrencyKeys, holdId, billableTokens,
                            result.budget().key(), plan.legs()));
                    case "ORG_SUSPENDED" -> Mono.error(new HyperLlmException(ErrorCode.TENANT_ORG_SUSPENDED));
                    case "RATE_LIMIT" -> Mono.error(new HyperLlmException(ErrorCode.RATE_LIMITED));
                    case "BUDGET_EXCEEDED" -> Mono.error(new BillingAdmissionException(
                            ErrorCode.PROJECT_BUDGET_EXCEEDED, projectBudgetService.secondsUntilNextMonth()));
                    case "BUDGET_UNAVAILABLE", "MISS_BUDGET" -> Mono.error(
                            new BillingAdmissionException(ErrorCode.PROJECT_BUDGET_UNAVAILABLE, 60L));
                    default -> Mono.error(new HyperLlmException(ErrorCode.BILLING_INSUFFICIENT_BALANCE));
                });
    }

    private Mono<AdmitResult> admitOnce(
            CallerContext caller,
            String modelCode,
            long holdTarget,
            long billableTokens,
            String requestId,
            long promptTokens,
            long floorCost,
            ResolvedPricing pricing,
            AllocationPlan plan,
            List<app.pagefault.hyperllm.domain.billing.AllocationLeg> floor,
            String holdId,
            ProjectBudgetService.BudgetContext budget
    ) {
        var floorUsage = new BillableUsage(promptTokens, 0L, 0L, 0L, 0L);
        PreHoldService.AdmitCommand command = new PreHoldService.AdmitCommand(
                holdId, caller.orgId(), caller.projectId(), caller.apiKeyId(), modelCode,
                plan.legs(),
                caller.keyRpmLimit(), caller.projRpmLimit(),
                caller.keyTpmLimit(), caller.projTpmLimit(), billableTokens,
                caller.keyMaxConcurrency(), caller.projMaxConcurrency(), caller.orgMaxConcurrency(),
                properties.getBilling().getLeaseTtlSeconds(), requestId, floor,
                AllocationDetailCodec.encodeContextV1(pricing, floorUsage),
                budget.month(), budget.key(), holdTarget,
                budget.hardCapMicros(), budget.softCapMicros(), floorCost);
        return preHoldService.admit(command).map(status -> new AdmitResult(status, budget));
    }


    private static List<String> concurrencyKeys(CallerContext caller) {
        UUID effectiveKeyId = caller.apiKeyId() != null ? caller.apiKeyId() : caller.projectId();
        List<String> keys = new java.util.ArrayList<>(3);
        if (caller.keyMaxConcurrency() != null && caller.keyMaxConcurrency() > 0) {
            keys.add(GatewayRedisKeys.concurrencyCounter("key", effectiveKeyId));
        }
        if (caller.projMaxConcurrency() != null && caller.projMaxConcurrency() > 0) {
            keys.add(GatewayRedisKeys.concurrencyCounter("proj", caller.projectId()));
        }
        if (caller.orgMaxConcurrency() > 0) {
            keys.add(GatewayRedisKeys.concurrencyCounter("org", caller.orgId()));
        }
        return keys;
    }

    private Mono<Void> releaseHold(PreHoldService.Lease lease) {
        return preHoldService.release(lease).onErrorResume(ex -> {
            log.warn("Cannot release hold {}", lease.holdId(), ex);
            return Mono.empty();
        });
    }

    private Mono<CallerContext> authenticateCaller(String rawToken, String clientIp) {
        if (rawToken == null || rawToken.isBlank()) {
            return Mono.error(new HyperLlmException(ErrorCode.AUTH_INVALID_KEY, "Missing API key"));
        }
        if (ApiKeyGenerator.isSandboxKey(rawToken)) {
            return cacheService.resolveSandboxToken(rawToken)
                    .switchIfEmpty(Mono.error(() -> new HyperLlmException(ErrorCode.AUTH_INVALID_KEY, "Invalid or expired sandbox token")))
                    .flatMap(payload -> {
                        if (payload.expiresAt() == null || !payload.expiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
                            return Mono.error(new HyperLlmException(ErrorCode.KEY_EXPIRED, "Sandbox token expired"));
                        }
                        return Mono.just(new CallerContext(
                                payload.orgId(), payload.projectId(), null, payload.userId(),
                                null, payload.allowedModels(), null, null, null, null, null, null, 50, null, 10_000,
                                null, null
                        ));
                    });
        }
        if (!ApiKeyGenerator.isLiveKey(rawToken)) {
            return Mono.error(new HyperLlmException(ErrorCode.AUTH_INVALID_KEY));
        }
        return cacheService.resolveLiveKey(rawToken)
                .switchIfEmpty(Mono.error(() -> new HyperLlmException(ErrorCode.AUTH_INVALID_KEY)))
                .flatMap(ctx -> validateLiveKeyContext(ctx, clientIp));
    }

    private Mono<CallerContext> validateLiveKeyContext(GatewayKeyContext ctx, String clientIp) {
        if (ctx.apiKeyStatus() == ApiKeyStatus.REVOKED) {
            return Mono.error(new HyperLlmException(ErrorCode.KEY_ALREADY_REVOKED));
        }
        if (ctx.apiKeyStatus() != ApiKeyStatus.ACTIVE) {
            return Mono.error(new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "API key is disabled"));
        }
        if (ctx.apiKeyExpiresAt() != null && !ctx.apiKeyExpiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            return Mono.error(new HyperLlmException(ErrorCode.KEY_EXPIRED));
        }
        if (ctx.projectStatus() != ProjectStatus.ACTIVE) {
            return Mono.error(new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "Project is not active"));
        }
        if (ctx.orgStatus() != OrgStatus.ACTIVE) {
            return Mono.error(new HyperLlmException(ErrorCode.TENANT_ORG_SUSPENDED));
        }
        if (!CidrMatcher.isAllowed(clientIp, ctx.ipWhitelist())) {
            return Mono.error(new HyperLlmException(ErrorCode.AUTH_IP_BLOCKED));
        }
        return Mono.just(new CallerContext(
                ctx.orgId(), ctx.projectId(), ctx.apiKeyId(), null,
                ctx.keyAllowedModels(), ctx.projectAllowedModels(),
                ctx.keyRpmLimit(), ctx.projectRpmLimit(),
                ctx.keyTpmLimit(), ctx.projectTpmLimit(),
                ctx.keyMaxConcurrency(), ctx.projectMaxConcurrency(),
                ctx.orgMaxConcurrency(), ctx.orgDiscountRatioBps(), ctx.tierDefaultDiscountBps(),
                ctx.projectMonthlyHardCapMicros(), ctx.projectMonthlySoftCapMicros()
        ));
    }

    private static boolean isModelAllowed(CallerContext caller, String modelCode) {
        if (caller.projectAllowedModels() != null && !caller.projectAllowedModels().isEmpty()
                && !caller.projectAllowedModels().contains(modelCode)) {
            return false;
        }
        return caller.keyAllowedModels() == null || caller.keyAllowedModels().isEmpty()
                || caller.keyAllowedModels().contains(modelCode);
    }

    private String decryptChannelApiKey(GatewayChannelCandidate candidate) {
        try {
            String json = aesGcmCipher.decryptUtf8(candidate.encryptedCredentials(), candidate.channelId().toString());
            return extractApiKeyFromJson(json);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to decrypt upstream channel credentials", ex);
        }
    }

    static String extractApiKeyFromJson(String json) throws IOException {
        if (json == null || json.isBlank()) {
            return "";
        }
        try (JsonParser p = CREDENTIAL_JSON_FACTORY.createParser(json)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return "";
            }
            while (p.nextToken() != JsonToken.END_OBJECT && p.currentToken() != null) {
                String field = p.currentName();
                JsonToken val = p.nextToken();
                if ("apiKey".equals(field)) {
                    if (val == JsonToken.VALUE_STRING) {
                        return p.getText();
                    }
                    if (val == JsonToken.VALUE_NULL) {
                        return "";
                    }
                    if (val != null && val.isScalarValue()) {
                        return p.getValueAsString("");
                    }
                    return "";
                }
                p.skipChildren();
            }
            return "";
        }
    }

    private static ProtocolKind resolveOutboundProtocol(GatewayChannelCandidate candidate, ProtocolKind inboundProtocol) {
        ProtocolKind resolved = ProtocolKind.parseOrNull(candidate.resolveOutboundProtocolName(inboundProtocol.name()));
        return resolved != null ? resolved : ProtocolKind.OPENAI_CHAT;
    }

    private void applyUpstreamHeaders(
            HttpHeaders headers,
            GatewayChannelCandidate candidate,
            Map<String, String> extraHeaders,
            ProtocolKind outboundProtocol,
            String apiKey,
            String anthropicVersion,
            String anthropicBeta
    ) {
        Map<String, String> authHeaders = UpstreamEndpointResolver.buildAuthHeaders(
                candidate.authScheme().name(),
                outboundProtocol,
                apiKey,
                anthropicVersion,
                anthropicBeta
        );
        authHeaders.forEach(headers::set);
        if (extraHeaders != null && !extraHeaders.isEmpty()) {
            extraHeaders.forEach(headers::set);
        }
    }

    private static Throwable unwrapRetryError(Throwable ex) {
        if (Exceptions.isRetryExhausted(ex)) {
            Throwable cause = ex.getCause();
            if (cause instanceof RetryableCapacityException) {
                return new HyperLlmException(ErrorCode.CHANNEL_CAPACITY_EXCEEDED,
                        "Every eligible upstream channel is at capacity", ex);
            }
            return new HyperLlmException(ErrorCode.CHANNEL_UNAVAILABLE, "Upstream retries exhausted", ex);
        }
        if (ex instanceof RetryableUpstreamException) {
            return new HyperLlmException(ErrorCode.CHANNEL_UNAVAILABLE, "Upstream retries exhausted", ex);
        }
        return ex;
    }

    private static String extractToken(ServerHttpRequest request, ProtocolKind protocolKind) {
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring("Bearer ".length()).trim();
        }
        String xApiKey = request.getHeaders().getFirst("x-api-key");
        if (xApiKey != null && !xApiKey.isBlank()) {
            return xApiKey.trim();
        }
        String googKey = request.getHeaders().getFirst("x-goog-api-key");
        if (googKey != null && !googKey.isBlank()) {
            return googKey.trim();
        }
        if (protocolKind == ProtocolKind.GEMINI_GENERATE_CONTENT) {
            String queryKey = request.getQueryParams().getFirst("key");
            if (queryKey != null && !queryKey.isBlank()) {
                return queryKey.trim();
            }
        }
        return null;
    }

    private static String extractClientIp(ServerHttpRequest request) {
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        var remote = request.getRemoteAddress();
        return (remote != null && remote.getAddress() != null) ? remote.getAddress().getHostAddress() : "127.0.0.1";
    }

    private static String maskClientIp(String ip) {
        if (ip == null) {
            return "";
        }
        int lastDot = ip.lastIndexOf('.');
        if (lastDot > 0) {
            return ip.substring(0, lastDot) + ".0/24";
        }
        return ip;
    }
}
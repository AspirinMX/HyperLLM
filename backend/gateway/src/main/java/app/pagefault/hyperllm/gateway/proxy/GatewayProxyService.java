package app.pagefault.hyperllm.gateway.proxy;

import app.pagefault.hyperllm.common.crypto.AesGcmCipher;
import app.pagefault.hyperllm.common.crypto.ApiKeyGenerator;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.money.CostMath;
import app.pagefault.hyperllm.domain.billing.AllocationDetailCodec;
import app.pagefault.hyperllm.domain.billing.AllocationPlan;
import app.pagefault.hyperllm.domain.billing.BillableUsage;
import app.pagefault.hyperllm.domain.billing.EntitlementAllocator;
import app.pagefault.hyperllm.domain.billing.EntitlementSnapshot;
import app.pagefault.hyperllm.domain.pricing.PricingResolver;
import app.pagefault.hyperllm.domain.pricing.ResolvedPricing;
import app.pagefault.hyperllm.common.messaging.LlmRequestEvent;
import app.pagefault.hyperllm.common.net.CidrMatcher;
import app.pagefault.hyperllm.domain.model.enums.ApiKeyStatus;
import app.pagefault.hyperllm.domain.model.enums.AuthScheme;
import app.pagefault.hyperllm.domain.model.enums.ModelStatus;
import app.pagefault.hyperllm.domain.model.enums.OrgStatus;
import app.pagefault.hyperllm.domain.model.enums.ProjectStatus;
import app.pagefault.hyperllm.domain.model.enums.ProtocolFamily;
import app.pagefault.hyperllm.domain.model.routing.GatewayChannelCandidate;
import app.pagefault.hyperllm.domain.model.routing.GatewayModelRoute;
import app.pagefault.hyperllm.domain.model.routing.PricingRule;
import app.pagefault.hyperllm.domain.model.routing.VirtualModel;
import app.pagefault.hyperllm.domain.model.tenant.GatewayKeyContext;
import app.pagefault.hyperllm.gateway.billing.HeldLegCharger;
import app.pagefault.hyperllm.gateway.billing.PreHoldService;
import app.pagefault.hyperllm.gateway.billing.GatewayTelemetryService;
import app.pagefault.hyperllm.gateway.cache.GatewayConfigCacheService;
import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import app.pagefault.hyperllm.gateway.outbox.OutboxAdmissionGate;
import app.pagefault.hyperllm.gateway.routing.ChannelSelector;
import app.pagefault.hyperllm.gateway.routing.UpstreamWebClientPool;
import app.pagefault.hyperllm.protocol.adapter.ProtocolAdapter;
import app.pagefault.hyperllm.protocol.adapter.ProtocolRegistry;
import app.pagefault.hyperllm.protocol.adapter.StreamUsageTracker;
import app.pagefault.hyperllm.protocol.ir.CanonicalChatRequest;
import app.pagefault.hyperllm.protocol.ir.PreparedUpstreamRequest;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.TokenUsage;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.reactivestreams.Subscription;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
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
            Integer keyMaxConcurrency,
            Integer projMaxConcurrency,
            int orgMaxConcurrency,
            Integer orgDiscountRatioBps,
            int tierDefaultDiscountBps
    ) {
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
    private final GatewayTelemetryService telemetryService;
    private final GatewayProperties properties;
    private final ProtocolRegistry protocolRegistry;
    private final AesGcmCipher aesGcmCipher;
    private final ChannelSelector channelSelector;
    private final UpstreamWebClientPool webClientPool;
    private final app.pagefault.hyperllm.gateway.cache.EntitlementMirrorService mirrorService;
    private final ObjectMapper objectMapper;
    private final TokenEstimator tokenEstimator = new TokenEstimator();

    public Mono<Map<String, Object>> listModels(ServerWebExchange exchange) {
        String rawToken = extractToken(exchange.getRequest());
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

    public Mono<ResponseEntity<Flux<DataBuffer>>> proxyChatOrMessages(
            ServerWebExchange exchange,
            ProtocolKind protocolKind,
            byte[] rawBody
    ) {
        long requestStartNanos = System.nanoTime();
        String requestId = UuidV7.next().toString();
        String traceId = requestId.replace("-", "");
        String rawToken = extractToken(exchange.getRequest());
        String clientIp = extractClientIp(exchange.getRequest());

        ProtocolAdapter adapter = protocolRegistry.adapterFor(protocolKind);
        var canonical = adapter.parseRequest(rawBody);
        ProtocolFamily requiredFamily = (protocolKind == ProtocolKind.ANTHROPIC_MESSAGES)
                ? ProtocolFamily.ANTHROPIC
                : ProtocolFamily.OPENAI;

        return authenticateCaller(rawToken, clientIp)
                .flatMap(caller -> {
                    if (!isModelAllowed(caller, canonical.model())) {
                        return Mono.error(new HyperLlmException(ErrorCode.MODEL_NOT_ALLOWED));
                    }
                    return cacheService.resolveModelRoute(canonical.model())
                            .switchIfEmpty(Mono.error(new HyperLlmException(ErrorCode.MODEL_NOT_FOUND)))
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
                                List<GatewayChannelCandidate> candidates = route.candidates().stream()
                                        .filter(c -> c.protocolFamily() == requiredFamily)
                                        .toList();
                                if (candidates.isEmpty()) {
                                    return Mono.error(new HyperLlmException(
                                            ErrorCode.CHANNEL_UNAVAILABLE,
                                            "No active channel matches protocol " + requiredFamily
                                    ));
                                }
                                return preparePreHold(caller, route, canonical, requestId)
                                        .flatMap(lease -> dispatchWithRetry(
                                                exchange, adapter, protocolKind, rawBody, canonical.stream(),
                                                caller, route, candidates, lease, requestStartNanos, requestId, traceId, clientIp
                                        ).onErrorResume(ex -> releaseHold(lease).then(Mono.error(unwrapRetryError(ex)))));
                            });
                });
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> dispatchWithRetry(
            ServerWebExchange exchange,
            ProtocolAdapter adapter,
            ProtocolKind protocolKind,
            byte[] rawBody,
            boolean isStream,
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

        return Mono.defer(() -> {
            GatewayChannelCandidate candidate = channelSelector.selectNext(candidates, excludedChannels);
            if (candidate == null) {
                return Mono.error(new HyperLlmException(ErrorCode.CHANNEL_UNAVAILABLE, "All upstream channels exhausted"));
            }
            int attemptIndex = attemptCounter.getAndIncrement();
            int retryCount = Math.max(0, attemptIndex - 1);

            PreparedUpstreamRequest prepared = adapter.prepareUpstreamRequest(rawBody, candidate.upstreamModelName());
            String upstreamApiKey = decryptChannelApiKey(candidate);
            String targetUrl = buildTargetUrl(candidate, protocolKind);
            WebClient client = webClientPool.clientFor(candidate.providerCode());

            return executeSingleAttempt(
                    exchange, client, targetUrl, candidate, upstreamApiKey,
                    adapter, prepared, protocolKind, isStream,
                    caller, route, lease, excludedChannels,
                    requestStartNanos, requestId, traceId, clientIp, retryCount
            );
        }).retryWhen(Retry.max(properties.getUpstream().getMaxRetryAttempts())
                .filter(RetryableUpstreamException.class::isInstance));
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> executeSingleAttempt(
            ServerWebExchange exchange,
            WebClient client,
            String targetUrl,
            GatewayChannelCandidate candidate,
            String upstreamApiKey,
            ProtocolAdapter adapter,
            PreparedUpstreamRequest prepared,
            ProtocolKind protocolKind,
            boolean isStream,
            CallerContext caller,
            GatewayModelRoute route,
            PreHoldService.Lease lease,
            Set<UUID> excludedChannels,
            long requestStartNanos,
            String requestId,
            String traceId,
            String clientIp,
            int retryCount
    ) {
        Sinks.One<ResponseEntity<Flux<DataBuffer>>> responseSink = Sinks.one();
        AtomicReference<Subscription> upstreamSubRef = new AtomicReference<>();

        Flux<Void> upstreamExecution = client.post()
                .uri(targetUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> applyUpstreamAuth(headers, candidate, upstreamApiKey))
                .bodyValue(prepared.upstreamBody())
                .exchangeToFlux(clientResponse -> {
                    int status = clientResponse.statusCode().value();
                    if (status == 429 || status >= 500) {
                        excludedChannels.add(candidate.channelId());
                        return clientResponse.releaseBody()
                                .thenMany(Flux.error(new RetryableUpstreamException(
                                        status, "Upstream returned retryable status " + status)));
                    }
                    if (!isStream || !clientResponse.statusCode().is2xxSuccessful()) {
                        return clientResponse.bodyToMono(byte[].class)
                                .defaultIfEmpty(new byte[0])
                                .flatMapMany(bodyBytes -> handleNonStreamingResponse(
                                        exchange, adapter, prepared, protocolKind, status, bodyBytes,
                                        caller, route, candidate, lease, requestStartNanos,
                                        requestId, traceId, clientIp, retryCount
                                ).doOnNext(responseSink::tryEmitValue)
                                        .doOnError(responseSink::tryEmitError)
                                        .thenMany(Flux.empty()));
                    }
                    return bridgeStreamingResponse(
                            exchange, clientResponse, adapter, prepared, protocolKind,
                            caller, route, candidate, lease, requestStartNanos,
                            requestId, traceId, clientIp, retryCount,
                            responseSink, upstreamSubRef
                    );
                });

        upstreamExecution.doOnSubscribe(upstreamSubRef::set)
                .subscribe(
                        ignored -> {},
                        ex -> {
                            Throwable mapped = ex;
                            if (!(mapped instanceof RetryableUpstreamException) && !(mapped instanceof HyperLlmException)) {
                                excludedChannels.add(candidate.channelId());
                                mapped = new RetryableUpstreamException(502, "Upstream transport failure: " + mapped.getMessage());
                            }
                            responseSink.tryEmitError(mapped);
                        }
                );

        return responseSink.asMono().doOnCancel(() -> {
            Subscription sub = upstreamSubRef.get();
            if (sub != null) {
                sub.cancel();
            }
        });
    }

    private Flux<Void> bridgeStreamingResponse(
            ServerWebExchange exchange,
            org.springframework.web.reactive.function.client.ClientResponse clientResponse,
            ProtocolAdapter adapter,
            PreparedUpstreamRequest prepared,
            ProtocolKind protocolKind,
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
            AtomicReference<Subscription> upstreamSubRef
    ) {
        StreamUsageTracker tracker = adapter.newStreamTracker(prepared.canonicalRequest());
        AtomicBoolean settled = new AtomicBoolean(false);
        AtomicLong firstByteNanos = new AtomicLong(0L);
        DataBufferFactory bufferFactory = exchange.getResponse().bufferFactory();
        Sinks.Many<byte[]> frameSink = Sinks.many().unicast().onBackpressureBuffer();

        Flux<DataBuffer> clientFrames = frameSink.asFlux()
                .map(bufferFactory::wrap)
                .doOnCancel(() -> {
                    Subscription sub = upstreamSubRef.get();
                    if (sub != null) {
                        sub.cancel();
                    }
                })
                .doFinally(signal -> {
                    if (settled.get()) {
                        return;
                    }
                    TokenUsage fallback = tracker.finalizeUsage(false);
                    settleReactive(
                            settled, false, fallback,
                            requestStartNanos, firstByteNanos.get(),
                            requestId, traceId, caller, route, candidate,
                            candidate.upstreamModelName(),
                            protocolKind, true, 200, "STREAM_INTERRUPTED", retryCount, clientIp, lease
                    ).subscribe();
                });

        responseSink.tryEmitValue(ResponseEntity.status(clientResponse.statusCode())
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(clientFrames));

        return Flux.using(() -> startHeartbeat(lease), heartbeat -> clientResponse.bodyToFlux(DataBuffer.class)
                .concatMapIterable(dataBuffer -> {
                    try {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        return tracker.feed(bytes);
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                })
                .concatWith(Flux.defer(() -> Flux.fromIterable(tracker.complete())))
                .concatMap(frame -> firstByteNanos.get() == 0L
                        ? preHoldService.markServed(lease)
                                .onErrorResume(ex -> {
                                    log.warn("Cannot mark first response frame for hold {}", lease.holdId(), ex);
                                    return Mono.empty();
                                })
                                .thenReturn(frame)
                        : Mono.just(frame))
                .doOnNext(frame -> {
                    firstByteNanos.compareAndSet(0L, System.nanoTime());
                    frameSink.tryEmitNext(frame);
                })
                .then(Mono.defer(() -> settleReactive(
                        settled, true, tracker.finalizeUsage(true),
                        requestStartNanos, firstByteNanos.get(),
                        requestId, traceId, caller, route, candidate,
                        candidate.upstreamModelName(),
                        protocolKind, true, 200, null, retryCount, clientIp, lease
                )))
                .then(Mono.fromRunnable(frameSink::tryEmitComplete))
                .onErrorResume(ex -> settleReactive(
                        settled, false, tracker.finalizeUsage(false),
                        requestStartNanos, firstByteNanos.get(),
                        requestId, traceId, caller, route, candidate,
                        candidate.upstreamModelName(),
                        protocolKind, true, 200, "STREAM_INTERRUPTED", retryCount, clientIp, lease
                ).then(Mono.fromRunnable(() -> frameSink.tryEmitError(ex))))
                .thenMany(Flux.<Void>empty()), Disposable::dispose);
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> handleNonStreamingResponse(
            ServerWebExchange exchange,
            ProtocolAdapter adapter,
            PreparedUpstreamRequest prepared,
            ProtocolKind protocolKind,
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
        if (status >= 200 && status < 300) {
            usage = adapter.extractNonStreamingUsage(bodyBytes)
                    .orElseGet(() -> new TokenUsage(
                            tokenEstimator.estimatePromptTokens(prepared.canonicalRequest()),
                            0, 0,
                            tokenEstimator.countText(prepared.canonicalRequest().model(), new String(bodyBytes)),
                            0, true
                    ));
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
                .body(Flux.just(exchange.getResponse().bufferFactory().wrap(bodyBytes))));
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
                        pricing.reasoningPriceMicros1m()),
                candidate.effectiveCostDiscountBps());

        return mirrorService.heldLegs(lease.holdId())
                .map(legs -> HeldLegCharger.charge(legs, exactCostMicros).legs())
                .flatMap(charges -> preHoldService.settle(lease, charges,
                        AllocationDetailCodec.encodeContextV1(resolved, billable), eventCreatedAt))
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

    private Disposable startHeartbeat(PreHoldService.Lease lease) {
        var billing = properties.getBilling();
        long ttlSeconds = billing.getLeaseTtlSeconds();
        long periodSeconds = Math.max(1, Math.min(billing.getHeartbeatMinIntervalSeconds(), ttlSeconds / 3));
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
            CanonicalChatRequest canonical,
            String requestId
    ) {
        outboxAdmissionGate.check();
        GatewayProperties.Billing billing = properties.getBilling();
        String modelCode = route.model().modelCode();
        long promptEstimate = Math.max(0L, tokenEstimator.estimatePromptTokens(canonical));
        int outputCap = canonical.maxTokens() != null && canonical.maxTokens() > 0
                ? canonical.maxTokens()
                : billing.getDefaultOutputTokens();

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
                    return preHoldService.reclaimExpired(billing.getLazyReclaimBatch())
                            .onErrorResume(ex -> Mono.just(0))
                            .then(mirrorService.read(caller.orgId(), modelCode, true))
                            .flatMap(mirror -> admitWithEstimate(
                                    caller, modelCode, target, billable, mirror.snapshot(),
                                    requestId, promptEstimate, floorCost, resolved));
                });
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
        outboxAdmissionGate.check();
        AllocationPlan plan = EntitlementAllocator.allocate(holdTarget, billableTokens, snapshot);
        if (plan.legs().isEmpty()) {
            return Mono.error(new HyperLlmException(ErrorCode.BILLING_INSUFFICIENT_BALANCE));
        }

        var floorUsage = new BillableUsage(promptTokens, 0L, 0L, 0L, 0L);
        var floor = HeldLegCharger.promptFloor(plan.legs(), snapshot, floorCost, promptTokens);
        String holdId = UuidV7.next().toString();
        PreHoldService.AdmitCommand command = new PreHoldService.AdmitCommand(
                holdId, caller.orgId(), caller.projectId(), caller.apiKeyId(), modelCode,
                plan.legs(),
                caller.keyRpmLimit(), caller.projRpmLimit(),
                caller.keyMaxConcurrency(), caller.projMaxConcurrency(), caller.orgMaxConcurrency(),
                properties.getBilling().getLeaseTtlSeconds(), requestId, floor,
                AllocationDetailCodec.encodeContextV1(pricing, floorUsage));
        List<String> concurrencyKeys = concurrencyKeys(caller);
        return preHoldService.admit(command)
                .flatMap(status -> switch (status) {
                    case "OK" -> Mono.just(new PreHoldService.Lease(
                            caller.orgId(), concurrencyKeys, holdId));
                    case "ORG_SUSPENDED" -> Mono.error(new HyperLlmException(ErrorCode.TENANT_ORG_SUSPENDED));
                    case "RATE_LIMIT" -> Mono.error(new HyperLlmException(ErrorCode.RATE_LIMITED));
                    default -> Mono.error(new HyperLlmException(ErrorCode.BILLING_INSUFFICIENT_BALANCE));
                });
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
                    .switchIfEmpty(Mono.error(new HyperLlmException(ErrorCode.AUTH_INVALID_KEY, "Invalid or expired sandbox token")))
                    .flatMap(payload -> {
                        if (payload.expiresAt() == null || !payload.expiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
                            return Mono.error(new HyperLlmException(ErrorCode.KEY_EXPIRED, "Sandbox token expired"));
                        }
                        return Mono.just(new CallerContext(
                                payload.orgId(), payload.projectId(), null, payload.userId(),
                                null, payload.allowedModels(), null, null, null, null, 50, null, 10_000
                        ));
                    });
        }
        if (!ApiKeyGenerator.isLiveKey(rawToken)) {
            return Mono.error(new HyperLlmException(ErrorCode.AUTH_INVALID_KEY));
        }
        return cacheService.resolveLiveKey(rawToken)
                .switchIfEmpty(Mono.error(new HyperLlmException(ErrorCode.AUTH_INVALID_KEY)))
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
                ctx.keyMaxConcurrency(), ctx.projectMaxConcurrency(),
                ctx.orgMaxConcurrency(), ctx.orgDiscountRatioBps(), ctx.tierDefaultDiscountBps()
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
            JsonNode root = objectMapper.readTree(json);
            return root.path("apiKey").asText("");
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to decrypt upstream channel credentials", ex);
        }
    }

    private static String buildTargetUrl(GatewayChannelCandidate candidate, ProtocolKind protocolKind) {
        String base = candidate.resolvedBaseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return switch (protocolKind) {
            case OPENAI_CHAT -> base + "/chat/completions";
            case ANTHROPIC_MESSAGES -> base + "/messages";
        };
    }

    private static void applyUpstreamAuth(HttpHeaders headers, GatewayChannelCandidate candidate, String apiKey) {
        if (candidate.authScheme() == AuthScheme.X_API_KEY) {
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");
        } else {
            headers.setBearerAuth(apiKey);
        }
    }

    private static Throwable unwrapRetryError(Throwable ex) {
        if (Exceptions.isRetryExhausted(ex) || ex instanceof RetryableUpstreamException) {
            return new HyperLlmException(ErrorCode.CHANNEL_UNAVAILABLE, "Upstream retries exhausted", ex);
        }
        return ex;
    }

    private static String extractToken(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring("Bearer ".length()).trim();
        }
        String xApiKey = request.getHeaders().getFirst("x-api-key");
        return xApiKey != null ? xApiKey.trim() : null;
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

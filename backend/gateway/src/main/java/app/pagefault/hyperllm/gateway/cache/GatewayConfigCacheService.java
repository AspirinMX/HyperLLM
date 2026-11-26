package app.pagefault.hyperllm.gateway.cache;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.common.cache.SandboxTokenPayload;
import app.pagefault.hyperllm.common.crypto.HmacSha256;
import app.pagefault.hyperllm.domain.model.billing.OrgModelPricingOverride;
import app.pagefault.hyperllm.domain.model.routing.GatewayChannelCandidate;
import app.pagefault.hyperllm.domain.model.routing.GatewayModelRoute;
import app.pagefault.hyperllm.domain.model.routing.VirtualModel;
import app.pagefault.hyperllm.domain.model.tenant.GatewayKeyContext;
import app.pagefault.hyperllm.domain.repository.ApiKeyRepository;
import app.pagefault.hyperllm.domain.repository.GatewayRouteRepository;
import app.pagefault.hyperllm.domain.repository.OrgModelPricingOverrideRepository;
import app.pagefault.hyperllm.domain.repository.VirtualModelRepository;
import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.ProviderCapabilities;
import app.pagefault.hyperllm.protocol.ir.TranslationPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Three-tier Gateway cache (Caffeine L1 -> Reactive Redis L2 -> SingleFlight offloaded domain/Postgres).
 */
@Slf4j
@Service
public class GatewayConfigCacheService {

    /** Allocation-light composite key replacing {@code orgId + ":" + virtualModelId} string concatenation. */
    private record OrgModelKey(UUID orgId, UUID virtualModelId) {
    }

    /**
     * Pre-parsed immutable routing metadata computed once when a {@link GatewayModelRoute} enters L1,
     * eliminating per-request Jackson DOM parsing of {@code metadata_json}, {@code extra_headers_json},
     * and {@link ProviderCapabilities}.
     */
    public record ParsedRouteMetadata(
            TranslationPolicy translationPolicy,
            String thinkingStyle,
            Map<UUID, Map<String, String>> channelExtraHeaders,
            Map<UUID, EnumMap<ProtocolKind, ProviderCapabilities>> channelCapabilities
    ) {
        public Map<String, String> extraHeadersFor(UUID channelId) {
            if (channelId == null) {
                return Map.of();
            }
            Map<String, String> headers = channelExtraHeaders.get(channelId);
            return headers != null ? headers : Map.of();
        }

        public ProviderCapabilities capabilitiesFor(UUID channelId, ProtocolKind outboundProtocol) {
            if (channelId == null || outboundProtocol == null) {
                return null;
            }
            EnumMap<ProtocolKind, ProviderCapabilities> byProto = channelCapabilities.get(channelId);
            return byProto != null ? byProto.get(outboundProtocol) : null;
        }

        public static ParsedRouteMetadata parse(GatewayModelRoute route, ObjectMapper mapper) {
            VirtualModel vm = route.model();
            TranslationPolicy policy = TranslationPolicy.parseOrDefault(
                    vm != null ? vm.translationPolicy() : null, TranslationPolicy.BEST_EFFORT);
            JsonNode metaTree = parseObject(vm != null ? vm.metadataJson() : null, mapper);
            String thinkingStyle = (metaTree != null && metaTree.hasNonNull("thinkingStyle"))
                    ? metaTree.get("thinkingStyle").asText()
                    : null;
            boolean supportsThinking = vm != null && vm.supportsThinking();

            List<GatewayChannelCandidate> candidates = route.candidates();
            Map<UUID, Map<String, String>> extraHeadersByChannel = new HashMap<>(
                    Math.max(4, candidates != null ? candidates.size() * 2 : 4));
            Map<UUID, EnumMap<ProtocolKind, ProviderCapabilities>> capsByChannel = new HashMap<>(
                    Math.max(4, candidates != null ? candidates.size() * 2 : 4));

            if (candidates != null) {
                for (GatewayChannelCandidate candidate : candidates) {
                    if (candidate == null || candidate.channelId() == null) {
                        continue;
                    }
                    UUID channelId = candidate.channelId();
                    Map<String, String> headers = parseExtraHeaders(candidate.extraHeadersJson(), mapper);
                    if (!headers.isEmpty()) {
                        extraHeadersByChannel.put(channelId, headers);
                    }
                    EnumMap<ProtocolKind, ProviderCapabilities> byProto = new EnumMap<>(ProtocolKind.class);
                    for (ProtocolKind proto : ProtocolKind.values()) {
                        byProto.put(proto, ProviderCapabilities.resolveWithParsedMetadata(
                                proto, candidate.providerCode(), supportsThinking, thinkingStyle, metaTree));
                    }
                    capsByChannel.put(channelId, byProto);
                }
            }
            return new ParsedRouteMetadata(
                    policy,
                    thinkingStyle,
                    Collections.unmodifiableMap(extraHeadersByChannel),
                    Collections.unmodifiableMap(capsByChannel)
            );
        }

        private static JsonNode parseObject(String json, ObjectMapper mapper) {
            if (json == null || json.isBlank() || "{}".equals(json.trim())) {
                return null;
            }
            try {
                JsonNode tree = mapper.readTree(json);
                return (tree != null && tree.isObject()) ? tree : null;
            } catch (Exception ignored) {
                return null;
            }
        }

        private static Map<String, String> parseExtraHeaders(String extraHeadersJson, ObjectMapper mapper) {
            JsonNode extra = parseObject(extraHeadersJson, mapper);
            if (extra == null || extra.isEmpty()) {
                return Map.of();
            }
            Map<String, String> out = new LinkedHashMap<>();
            extra.fields().forEachRemaining(e -> {
                if (e.getValue().isTextual()) {
                    out.put(e.getKey(), e.getValue().asText());
                }
            });
            return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
        }
    }

    private final Cache<String, Optional<GatewayKeyContext>> keyL1;
    private final Cache<String, Optional<GatewayModelRoute>> routeL1;
    private final Cache<String, ParsedRouteMetadata> parsedRouteMetaL1;
    private final Cache<String, List<VirtualModel>> publicCatalogL1;
    private final Cache<OrgModelKey, Optional<OrgModelPricingOverride>> overrideL1;

    /** Cache envelope so a "no override" answer can be cached explicitly instead of re-querying. */
    public record OverrideCacheEntry(OrgModelPricingOverride override) {
    }
    private final SingleFlight<String, Optional<GatewayKeyContext>> keySingleFlight = new SingleFlight<>();
    private final SingleFlight<String, Optional<GatewayModelRoute>> routeSingleFlight = new SingleFlight<>();

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final GatewayProperties properties;
    private final Scheduler domainFallbackScheduler;
    private final ApiKeyRepository apiKeyRepository;
    private final GatewayRouteRepository gatewayRouteRepository;
    private final VirtualModelRepository virtualModelRepository;
    private final OrgModelPricingOverrideRepository overrideRepository;

    public GatewayConfigCacheService(
            ReactiveStringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            GatewayProperties properties,
            @Qualifier("domainFallbackScheduler") Scheduler domainFallbackScheduler,
            ApiKeyRepository apiKeyRepository,
            GatewayRouteRepository gatewayRouteRepository,
            VirtualModelRepository virtualModelRepository,
            OrgModelPricingOverrideRepository overrideRepository
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.domainFallbackScheduler = domainFallbackScheduler;
        this.apiKeyRepository = apiKeyRepository;
        this.gatewayRouteRepository = gatewayRouteRepository;
        this.virtualModelRepository = virtualModelRepository;
        this.overrideRepository = overrideRepository;

        this.keyL1 = Caffeine.newBuilder()
                .maximumSize(properties.getCache().getMaxEntries())
                .expireAfterWrite(Duration.ofSeconds(properties.getCache().getL1KeyTtlSeconds()))
                .build();
        this.routeL1 = Caffeine.newBuilder()
                .maximumSize(properties.getCache().getMaxEntries())
                .expireAfterWrite(Duration.ofSeconds(properties.getCache().getL1RouteTtlSeconds()))
                .build();
        this.parsedRouteMetaL1 = Caffeine.newBuilder()
                .maximumSize(properties.getCache().getMaxEntries())
                .expireAfterWrite(Duration.ofSeconds(properties.getCache().getL1RouteTtlSeconds()))
                .build();
        this.publicCatalogL1 = Caffeine.newBuilder()
                .maximumSize(16)
                .expireAfterWrite(Duration.ofSeconds(properties.getCache().getL1RouteTtlSeconds()))
                .build();
        this.overrideL1 = Caffeine.newBuilder()
                .maximumSize(properties.getCache().getMaxEntries())
                .expireAfterWrite(Duration.ofSeconds(properties.getCache().getL1RouteTtlSeconds()))
                .build();
    }

    public Mono<GatewayKeyContext> resolveLiveKey(String rawKey) {
        String v1Hash = HmacSha256.hmacHex(rawKey, properties.getSecurity().getKeyPepperV1());
        return resolveByKeyHash(v1Hash).switchIfEmpty(Mono.defer(() -> {
            String v2 = properties.getSecurity().getKeyPepperV2();
            if (v2 == null || v2.isBlank()) {
                return Mono.empty();
            }
            return resolveByKeyHash(HmacSha256.hmacHex(rawKey, v2));
        }));
    }

    public Mono<SandboxTokenPayload> resolveSandboxToken(String rawToken) {
        String key = GatewayRedisKeys.sandboxToken(HmacSha256.sha256Hex(rawToken));
        return redisTemplate.opsForValue().get(key)
                .flatMap(json -> deserialize(json, SandboxTokenPayload.class))
                .onErrorResume(ex -> Mono.empty());
    }

    public Mono<GatewayModelRoute> resolveModelRoute(String modelCode) {
        Optional<GatewayModelRoute> l1 = routeL1.getIfPresent(modelCode);
        if (l1 != null) {
            return Mono.justOrEmpty(l1);
        }
        String redisKey = GatewayRedisKeys.modelRoute(modelCode);
        return redisTemplate.opsForValue().get(redisKey)
                .flatMap(json -> deserialize(json, GatewayModelRoute.class))
                .doOnNext(route -> {
                    routeL1.put(modelCode, Optional.of(route));
                    parsedRouteMetaL1.put(modelCode, ParsedRouteMetadata.parse(route, objectMapper));
                })
                .switchIfEmpty(Mono.defer(() -> routeSingleFlight.execute(modelCode, () -> loadRouteFromDomain(modelCode))
                        .flatMap(Mono::justOrEmpty)));
    }

    /**
     * Returns L1-cached pre-parsed routing metadata (translation policy, thinking style,
     * per-channel extra headers, and per-channel {@link ProviderCapabilities}).
     */
    public ParsedRouteMetadata resolveParsedMetadata(GatewayModelRoute route) {
        if (route == null || route.model() == null) {
            return null;
        }
        String modelCode = route.model().modelCode();
        if (modelCode == null) {
            return ParsedRouteMetadata.parse(route, objectMapper);
        }
        return parsedRouteMetaL1.get(modelCode, k -> ParsedRouteMetadata.parse(route, objectMapper));
    }

    public Mono<List<VirtualModel>> listPublicModels() {
        List<VirtualModel> cached = publicCatalogL1.getIfPresent("ALL");
        if (cached != null) {
            return Mono.just(cached);
        }
        return Mono.fromCallable(virtualModelRepository::listPublicActive)
                .subscribeOn(domainFallbackScheduler)
                .doOnNext(models -> publicCatalogL1.put("ALL", models));
    }

    /**
     * Resolves the effective model-level override for an organisation. The cache key uses the
     * virtual model id, not the model code, because the CDC event only carries the id.
     */
    public Mono<OrgModelPricingOverride> resolvePricingOverride(UUID orgId, UUID virtualModelId) {
        OrgModelKey cacheKey = new OrgModelKey(orgId, virtualModelId);
        Optional<OrgModelPricingOverride> l1 = overrideL1.getIfPresent(cacheKey);
        if (l1 != null) {
            return Mono.justOrEmpty(l1);
        }
        String redisKey = GatewayRedisKeys.modelOverride(orgId, virtualModelId);
        return redisTemplate.opsForValue().get(redisKey)
                .flatMap(json -> deserialize(json, OverrideCacheEntry.class))
                .doOnNext(entry -> overrideL1.put(cacheKey, Optional.ofNullable(entry.override())))
                .flatMap(entry -> Mono.justOrEmpty(entry.override()))
                .switchIfEmpty(Mono.defer(() -> loadOverrideFromDomain(cacheKey, redisKey, orgId, virtualModelId)));
    }

    private Mono<OrgModelPricingOverride> loadOverrideFromDomain(
            OrgModelKey cacheKey, String redisKey, UUID orgId, UUID virtualModelId) {
        return Mono.fromCallable(() -> overrideRepository.findEffective(
                        orgId, virtualModelId, OffsetDateTime.now(ZoneOffset.UTC)))
                .subscribeOn(domainFallbackScheduler)
                .flatMap(opt -> {
                    overrideL1.put(cacheKey, opt);
                    OverrideCacheEntry entry = new OverrideCacheEntry(opt.orElse(null));
                    return serialize(entry)
                            .flatMap(json -> redisTemplate.opsForValue()
                                    .set(redisKey, json, Duration.ofSeconds(properties.getCache().getL1RouteTtlSeconds()))
                                    .onErrorReturn(false)
                                    .thenReturn(entry))
                            .flatMap(saved -> Mono.justOrEmpty(saved.override()));
                });
    }

    public void evictAllOverridesL1() {
        overrideL1.invalidateAll();
    }

    public void evictKeyL1(String keyHash) {
        if (keyHash == null || keyHash.isBlank()) {
            keyL1.invalidateAll();
        } else {
            keyL1.invalidate(keyHash);
        }
    }

    public void evictAllKeysL1() {
        keyL1.invalidateAll();
    }

    public void evictRouteL1(String modelCode) {
        if (modelCode == null || modelCode.isBlank()) {
            routeL1.invalidateAll();
            parsedRouteMetaL1.invalidateAll();
        } else {
            routeL1.invalidate(modelCode);
            parsedRouteMetaL1.invalidate(modelCode);
        }
        publicCatalogL1.invalidateAll();
    }

    public void evictAllRoutesL1() {
        routeL1.invalidateAll();
        parsedRouteMetaL1.invalidateAll();
        publicCatalogL1.invalidateAll();
    }

    private Mono<GatewayKeyContext> resolveByKeyHash(String keyHash) {
        Optional<GatewayKeyContext> l1 = keyL1.getIfPresent(keyHash);
        if (l1 != null) {
            return Mono.justOrEmpty(l1);
        }
        String redisKey = GatewayRedisKeys.keyConfig(keyHash);
        return redisTemplate.opsForValue().get(redisKey)
                .flatMap(json -> deserialize(json, GatewayKeyContext.class))
                .doOnNext(ctx -> keyL1.put(keyHash, Optional.of(ctx)))
                .switchIfEmpty(Mono.defer(() -> keySingleFlight.execute(keyHash, () -> loadKeyFromDomain(keyHash))
                        .flatMap(Mono::justOrEmpty)));
    }

    private Mono<Optional<GatewayKeyContext>> loadKeyFromDomain(String keyHash) {
        return Mono.fromCallable(() -> apiKeyRepository.findGatewayKeyContextByHash(keyHash))
                .subscribeOn(domainFallbackScheduler)
                .flatMap(opt -> {
                    keyL1.put(keyHash, opt);
                    if (opt.isEmpty()) {
                        return Mono.just(opt);
                    }
                    return serialize(opt.get())
                            .flatMap(json -> redisTemplate.opsForValue()
                                    .set(GatewayRedisKeys.keyConfig(keyHash), json,
                                            Duration.ofSeconds(properties.getCache().getL2KeyTtlSeconds()))
                                    .onErrorReturn(false)
                                    .thenReturn(opt));
                });
    }

    private Mono<Optional<GatewayModelRoute>> loadRouteFromDomain(String modelCode) {
        return Mono.fromCallable(() -> gatewayRouteRepository.findActiveRouteByModelCode(
                        modelCode, OffsetDateTime.now(ZoneOffset.UTC)))
                .subscribeOn(domainFallbackScheduler)
                .flatMap(opt -> {
                    routeL1.put(modelCode, opt);
                    if (opt.isEmpty()) {
                        parsedRouteMetaL1.invalidate(modelCode);
                        return Mono.just(opt);
                    }
                    parsedRouteMetaL1.put(modelCode, ParsedRouteMetadata.parse(opt.get(), objectMapper));
                    return serialize(opt.get())
                            .flatMap(json -> redisTemplate.opsForValue()
                                    .set(GatewayRedisKeys.modelRoute(modelCode), json,
                                            Duration.ofSeconds(properties.getCache().getL2RouteTtlSeconds()))
                                    .onErrorReturn(false)
                                    .thenReturn(opt));
                });
    }

    private Mono<String> serialize(Object obj) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(obj));
    }

    private <T> Mono<T> deserialize(String json, Class<T> type) {
        return Mono.fromCallable(() -> objectMapper.readValue(json, type))
                .onErrorResume(ex -> {
                    log.warn("Failed to deserialize cached JSON as {}", type.getSimpleName(), ex);
                    return Mono.empty();
                });
    }
}
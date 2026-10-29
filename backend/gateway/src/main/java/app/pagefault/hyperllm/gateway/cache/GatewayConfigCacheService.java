package app.pagefault.hyperllm.gateway.cache;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.common.cache.SandboxTokenPayload;
import app.pagefault.hyperllm.common.crypto.HmacSha256;
import app.pagefault.hyperllm.domain.model.billing.OrgModelPricingOverride;
import app.pagefault.hyperllm.domain.model.routing.GatewayModelRoute;
import app.pagefault.hyperllm.domain.model.routing.VirtualModel;
import app.pagefault.hyperllm.domain.model.tenant.GatewayKeyContext;
import app.pagefault.hyperllm.domain.repository.ApiKeyRepository;
import app.pagefault.hyperllm.domain.repository.GatewayRouteRepository;
import app.pagefault.hyperllm.domain.repository.OrgModelPricingOverrideRepository;
import app.pagefault.hyperllm.domain.repository.VirtualModelRepository;
import app.pagefault.hyperllm.gateway.config.GatewayProperties;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Three-tier Gateway cache (Caffeine L1 -> Reactive Redis L2 -> SingleFlight offloaded domain/Postgres).
 */
@Slf4j
@Service
public class GatewayConfigCacheService {

    private final Cache<String, Optional<GatewayKeyContext>> keyL1;
    private final Cache<String, Optional<GatewayModelRoute>> routeL1;
    private final Cache<String, List<VirtualModel>> publicCatalogL1;
    private final Cache<String, Optional<OrgModelPricingOverride>> overrideL1;

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
                .doOnNext(route -> routeL1.put(modelCode, Optional.of(route)))
                .switchIfEmpty(Mono.defer(() -> routeSingleFlight.execute(modelCode, () -> loadRouteFromDomain(modelCode))
                        .flatMap(Mono::justOrEmpty)));
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
        String cacheKey = orgId + ":" + virtualModelId;
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
            String cacheKey, String redisKey, UUID orgId, UUID virtualModelId) {
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
        } else {
            routeL1.invalidate(modelCode);
        }
        publicCatalogL1.invalidateAll();
    }

    public void evictAllRoutesL1() {
        routeL1.invalidateAll();
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
                        return Mono.just(opt);
                    }
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
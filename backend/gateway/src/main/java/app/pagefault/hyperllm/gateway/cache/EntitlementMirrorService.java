package app.pagefault.hyperllm.gateway.cache;

import app.pagefault.hyperllm.common.billing.LedgerLagSignal;
import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.gateway.billing.BillingAdmissionException;
import app.pagefault.hyperllm.domain.billing.EntitlementSnapshot;
import app.pagefault.hyperllm.domain.model.billing.BonusGrant;
import app.pagefault.hyperllm.domain.model.billing.ResourcePack;
import app.pagefault.hyperllm.domain.model.billing.Wallet;
import app.pagefault.hyperllm.domain.repository.BonusGrantRepository;
import app.pagefault.hyperllm.domain.repository.ResourcePackRepository;
import app.pagefault.hyperllm.domain.model.tenant.Organization;
import app.pagefault.hyperllm.domain.repository.OrganizationRepository;
import app.pagefault.hyperllm.domain.repository.WalletRepository;
import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads and fills the per-batch mirror.
 *
 * <p>A read never writes balances: it returns what Redis holds, with the held amounts already
 * subtracted, so the allocator cannot hand out a batch another request reserved. A miss loads the
 * tenant once and retries; the load itself only creates missing keys, because a key that already
 * exists may have been debited since the database row was read.
 */
@Slf4j
@Service
public class EntitlementMirrorService {

    /**
     * Mirror state of one tenant for one model, ready to be turned into an allocation.
     *
     * @param loaded whether the batches were ever filled; a wallet written by the control plane has
     *               a hash but no loads behind it, and must not be served as if it were complete
     */
    public record Mirror(EntitlementSnapshot snapshot, String orgStatus, boolean loaded) {
    }

    private static final long NO_EXPIRY_EPOCH_MS = 4_102_444_800_000L;
    private static final String MISS_WALLET = "MISS_WALLET";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final GatewayProperties properties;
    private final OrganizationRepository organizationRepository;
    private final WalletRepository walletRepository;
    private final ResourcePackRepository packRepository;
    private final BonusGrantRepository grantRepository;
    private final Scheduler domainFallbackScheduler;
    private final RedisScript<List> snapshotScript;
    private final RedisScript<List> loadScript;
    private final LedgerHealthGate healthGate;
    private final Cache<String, Boolean> renewals;
    private final SingleFlight<String, Boolean> loads = new SingleFlight<>();
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate readSnapshot;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("rawtypes")
    public EntitlementMirrorService(
            ReactiveStringRedisTemplate redisTemplate,
            GatewayProperties properties,
            OrganizationRepository organizationRepository,
            WalletRepository walletRepository,
            ResourcePackRepository packRepository,
            BonusGrantRepository grantRepository,
            @Qualifier("domainFallbackScheduler") Scheduler domainFallbackScheduler,
            @Qualifier("preholdSnapshotLuaScript") RedisScript<List> snapshotScript,
            @Qualifier("loadTenantLuaScript") RedisScript<List> loadScript,
            LedgerHealthGate healthGate,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.organizationRepository = organizationRepository;
        this.walletRepository = walletRepository;
        this.packRepository = packRepository;
        this.grantRepository = grantRepository;
        this.domainFallbackScheduler = domainFallbackScheduler;
        this.snapshotScript = snapshotScript;
        this.loadScript = loadScript;
        this.healthGate = healthGate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        readSnapshot = new TransactionTemplate(transactionManager);
        readSnapshot.setReadOnly(true);
        readSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        long ttl = Math.max(1, properties.getCache().getSlidingTtlSeconds());
        long interval = Math.max(0, Math.min(properties.getCache().getRenewThrottleSeconds(), ttl / 2));
        this.renewals = Caffeine.newBuilder()
                .maximumSize(properties.getCache().getMaxEntries())
                .expireAfterWrite(Duration.ofSeconds(interval))
                .build();
    }

    /**
     * @param renew whether this call should extend the TTLs of everything it touched
     */
    public Mono<Mirror> read(UUID orgId, String modelCode, boolean renew) {
        return healthGate.current().flatMap(signal -> readOnce(orgId, modelCode, renew, signal)
                .switchIfEmpty(Mono.defer(() -> {
                    if (signal.degraded()) {
                        return blockedRebuild(orgId, signal);
                    }
                    return load(orgId, modelCode, true)
                            .then(healthGate.current().flatMap(fresh -> readOnce(orgId, modelCode, renew, fresh)));
                })))
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "tenant mirror still missing after load, org=" + orgId)));
    }

    /** @return {@code null} when the wallet has not been mirrored yet. */
    private Mono<Mirror> readOnce(UUID orgId, String modelCode, boolean renew, LedgerLagSignal signal) {
        long ttlMillis = properties.getCache().getSlidingTtlSeconds() * 1000L;
        List<String> keys = List.of(
                GatewayRedisKeys.wallet(orgId),
                GatewayRedisKeys.pack(orgId, modelCode),
                GatewayRedisKeys.bonus(orgId));
        String wallet = keys.get(0);
        String packs = keys.get(1);
        String orgRenew = renewalMode(wallet, renew, signal);
        String packRenew = renewalMode(packs, renew, signal);
        List<String> args = List.of(
                String.valueOf(System.currentTimeMillis()), orgRenew, String.valueOf(ttlMillis),
                GatewayRedisKeys.packItemPrefix(), GatewayRedisKeys.bonusItemPrefix(), packRenew, modelCode);
        return redisTemplate.execute(snapshotScript, keys, args)
                .next()
                .map(values -> decode(text(values)))
                // Reactor forbids a null from map; an empty Mono is what "not mirrored yet" means.
                .filter(mirror -> !MISS_WALLET.equals(mirror.orgStatus()) && mirror.loaded())
                .doOnSuccess(mirror -> {
                    if (mirror == null) {
                        renewals.invalidate(wallet);
                        renewals.invalidate(packs);
                    }
                })
                .doOnError(ex -> {
                    renewals.invalidate(wallet);
                    renewals.invalidate(packs);
                });
    }

    private String renewalMode(String scope, boolean renew, LedgerLagSignal signal) {
        if (signal.degraded()) {
            renewals.invalidate(scope);
            return "2";
        }
        return renew && renewals.asMap().putIfAbsent(scope, Boolean.TRUE) == null ? "1" : "0";
    }

    /** Coalesces cold reads for the same tenant and model while the snapshot is being filled. */
    public Mono<Void> load(UUID orgId, String modelCode) {
        return load(orgId, modelCode, false);
    }

    private Mono<Void> load(UUID orgId, String modelCode, boolean enforceGate) {
        String flightKey = GatewayRedisKeys.pack(orgId, modelCode) + (enforceGate ? "" : ":forced");
        return Mono.defer(() -> loads.execute(flightKey, () ->
                Mono.fromCallable(() -> readSnapshot.execute(status -> buildLoadArguments(orgId, modelCode)))
                        .subscribeOn(domainFallbackScheduler)
                        .flatMap(args -> healthGate.current().flatMap(signal -> {
                            if (enforceGate && signal.degraded()) {
                                return blockedRebuild(orgId, signal);
                            }
                            List<String> keys = List.of(GatewayRedisKeys.wallet(orgId),
                                    GatewayRedisKeys.pack(orgId, modelCode), GatewayRedisKeys.bonus(orgId),
                                    GatewayRedisKeys.ledgerPosition(orgId), GatewayRedisKeys.settleWaiting(orgId));
                            return redisTemplate.execute(loadScript, keys, args).next().flatMap(raw -> {
                                List<String> result = text(raw);
                                if ("UNFOLDED_DEBIT".equals(result.getFirst())
                                        || "STALE_CHECKPOINT".equals(result.getFirst())) {
                                    return Mono.error(new BillingAdmissionException(ErrorCode.LEDGER_BACKLOG, 1));
                                }
                                recordRebuildDelta(result);
                                return Mono.just(true);
                            });
                        })))).then();
    }

    private <T> Mono<T> blockedRebuild(UUID orgId, LedgerLagSignal signal) {
        meterRegistry.counter("gateway_rebuild_while_degraded_total").increment();
        log.debug("Ledger backlog prevented a mirror rebuild org={}", orgId);
        return Mono.error(new BillingAdmissionException(ErrorCode.LEDGER_BACKLOG, signal.retryAfterSeconds()));
    }

    private void recordRebuildDelta(List<String> result) {
        for (int i = 1; i < result.size(); i += 3) {
            String bucket = result.get(i);
            String live = result.get(i + 1);
            if (live.isEmpty()) {
                meterRegistry.counter("gateway_rebuild_live_balance_missing_total", "bucket", bucket).increment();
                continue;
            }
            long difference = Long.parseLong(live) - Long.parseLong(result.get(i + 2));
            String direction = difference < 0 ? "live_lower" : difference > 0 ? "live_higher" : "equal";
            meterRegistry.summary("gateway_rebuild_stale_delta_micros", "bucket", bucket, "direction", direction)
                    .record(Math.abs(difference));
        }
    }

    /** The batches a hold froze, used to charge exactly what was reserved. */
    public Mono<List<app.pagefault.hyperllm.domain.billing.AllocationLeg>> heldLegs(String holdId) {
        return redisTemplate.opsForHash()
                .get(GatewayRedisKeys.hold(holdId), "legs")
                .map(value -> toLegs(decodeLegs(String.valueOf(value))))
                .defaultIfEmpty(java.util.List.of());
    }

    private static java.util.List<app.pagefault.hyperllm.domain.billing.AllocationLeg> toLegs(List<String[]> raw) {
        java.util.List<app.pagefault.hyperllm.domain.billing.AllocationLeg> legs = new java.util.ArrayList<>();
        for (String[] parts : raw) {
            app.pagefault.hyperllm.domain.billing.AllocationType type =
                    app.pagefault.hyperllm.domain.billing.AllocationType.valueOf(parts[0]);
            java.util.UUID id = parts[1] == null || parts[1].isEmpty() ? null : java.util.UUID.fromString(parts[1]);
            long units = Long.parseLong(parts[2]);
            long micros = Long.parseLong(parts[3]);
            legs.add(new app.pagefault.hyperllm.domain.billing.AllocationLeg(type, id, units, micros));
        }
        return legs;
    }

    private List<String> buildLoadArguments(UUID orgId, String modelCode) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Wallet wallet = walletRepository.findByOrgId(orgId).orElseThrow();
        Organization organization = organizationRepository.findById(orgId).orElse(null);
        String orgStatus = organization == null ? "ACTIVE" : organization.status().name();
        List<ResourcePack> packs = packRepository.listConsumableByOrgAndModel(orgId, modelCode, now);
        List<BonusGrant> grants = grantRepository.listActiveByOrg(orgId);

        List<String> args = new ArrayList<>();
        args.add(String.valueOf(properties.getCache().getSlidingTtlSeconds() * 1000L));
        args.add(wallet.cashBalanceMicros() + "|0|" + orgStatus + "|" + wallet.version());
        args.add(String.valueOf(packs.size()));
        for (ResourcePack pack : packs) {
            args.add(pack.id() + "|" + pack.remainingUnits() + "|0|" + pack.unitType() + "|"
                    + epochMillis(pack.expiresAt()));
        }
        long mirroredMicros = grants.stream().mapToLong(BonusGrant::remainingMicros).sum();
        long unmirrored = Math.max(0L, wallet.bonusBalanceMicros() - mirroredMicros);

        List<String> grantArgs = new ArrayList<>();
        for (BonusGrant grant : grants) {
            if (grant.remainingMicros() > 0 && (grant.expiresAt() == null || grant.expiresAt().isAfter(now))) {
                grantArgs.add(grant.id() + "|" + grant.remainingMicros() + "|0|"
                        + epochMillis(grant.expiresAt()) + "|" + encodeModelScope(grant.applicableModels()));
            }
        }
        if (unmirrored > 0L) {
            // Balance granted before batches existed: carried as one synthetic batch so it stays
            // spendable and is recognisable on the settlement side.
            grantArgs.add(app.pagefault.hyperllm.common.billing.LegacyBonusBatch.idFor(orgId)
                    + "|" + unmirrored + "|0|"
                    + app.pagefault.hyperllm.common.billing.LegacyBonusBatch.NO_EXPIRY_EPOCH_MS + "|legacy");
        }
        args.add(String.valueOf(grantArgs.size()));
        args.addAll(grantArgs);
        args.add(GatewayRedisKeys.packItemPrefix());
        args.add(GatewayRedisKeys.bonusItemPrefix());
        return args;
    }

    private String encodeModelScope(List<String> models) {
        if (models == null) {
            return "*";
        }
        try {
            return objectMapper.writeValueAsString(models);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot encode bonus grant scope", ex);
        }
    }

    private static long epochMillis(OffsetDateTime expiresAt) {
        return expiresAt == null ? NO_EXPIRY_EPOCH_MS : expiresAt.toInstant().toEpochMilli();
    }

    private static List<String> text(List<?> raw) {
        List<String> values = new ArrayList<>();
        for (Object element : raw) {
            if (element instanceof byte[] bytes) {
                values.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            } else {
                values.add(String.valueOf(element));
            }
        }
        return values;
    }

    static List<String[]> decodeLegs(String raw) {
        List<String[]> legs = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return legs;
        }
        for (String segment : raw.split(";")) {
            String[] parts = segment.split("\\|", -1);
            if (parts.length == 4) {
                legs.add(parts);
            }
        }
        return legs;
    }

    static Mirror decode(List<String> values) {
        if (values.isEmpty() || MISS_WALLET.equals(values.get(0))) {
            return new Mirror(EntitlementSnapshot.empty(0L), MISS_WALLET, false);
        }
        long cash = Long.parseLong(values.get(1));
        long heldCash = Long.parseLong(values.get(2));
        String status = values.get(3);
        int cursor = 4;

        List<EntitlementSnapshot.PackBucket> packs = new ArrayList<>();
        int packCount = Integer.parseInt(values.get(cursor++));
        for (int i = 0; i < packCount; i++) {
            UUID id = UUID.fromString(values.get(cursor));
            long remaining = Long.parseLong(values.get(cursor + 1));
            long held = Long.parseLong(values.get(cursor + 2));
            String unitType = values.get(cursor + 3);
            packs.add(new EntitlementSnapshot.PackBucket(
                    id, app.pagefault.hyperllm.domain.model.enums.PackUnitType.valueOf(unitType),
                    Math.max(0L, remaining - held)));
            cursor += 5;
        }

        List<EntitlementSnapshot.BonusBucket> grants = new ArrayList<>();
        int grantCount = Integer.parseInt(values.get(cursor++));
        for (int i = 0; i < grantCount; i++) {
            UUID id = UUID.fromString(values.get(cursor));
            long remaining = Long.parseLong(values.get(cursor + 1));
            long held = Long.parseLong(values.get(cursor + 2));
            grants.add(new EntitlementSnapshot.BonusBucket(id, Math.max(0L, remaining - held)));
            cursor += 4;
        }
        boolean loaded = cursor < values.size() && "1".equals(values.get(cursor));
        return new Mirror(new EntitlementSnapshot(packs, grants, Math.max(0L, cash - heldCash)), status, loaded);
    }
}

package app.pagefault.hyperllm.gateway.cache;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

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
            LedgerHealthGate healthGate
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
    }

    /**
     * @param renew whether this call should extend the TTLs of everything it touched
     */
    public Mono<Mirror> read(UUID orgId, String modelCode, boolean renew) {
        return readOnce(orgId, modelCode, renew)
                .switchIfEmpty(Mono.defer(() -> {
                    if (healthGate.blocksRebuild()) {
                        // Rebuilding now would write a snapshot known to trail the deductions the
                        // gateway has already applied, handing the tenant back money it has spent.
                        return Mono.error(new app.pagefault.hyperllm.common.error.HyperLlmException(
                                app.pagefault.hyperllm.common.error.ErrorCode.LEDGER_BACKLOG));
                    }
                    return load(orgId, modelCode).then(readOnce(orgId, modelCode, renew));
                }))
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "tenant mirror still missing after load, org=" + orgId)));
    }

    /** @return {@code null} when the wallet has not been mirrored yet. */
    private Mono<Mirror> readOnce(UUID orgId, String modelCode, boolean renew) {
        long ttlMillis = properties.getCache().getSlidingTtlSeconds() * 1000L;
        List<String> keys = List.of(
                GatewayRedisKeys.wallet(orgId),
                GatewayRedisKeys.pack(orgId, modelCode),
                GatewayRedisKeys.bonus(orgId));
        List<String> args = List.of(
                String.valueOf(System.currentTimeMillis()),
                // Freezing is preferred over renewing while the ledger is behind, so a touched key
                // cannot expire and be rebuilt from a stale snapshot.
                renew ? (healthGate.blocksRebuild() ? "2" : "1") : "0",
                String.valueOf(ttlMillis),
                GatewayRedisKeys.packItemPrefix(),
                GatewayRedisKeys.bonusItemPrefix());
        return redisTemplate.execute(snapshotScript, keys, args)
                .next()
                .map(values -> decode(text(values)))
                // Reactor forbids a null from map; an empty Mono is what "not mirrored yet" means.
                .filter(mirror -> !MISS_WALLET.equals(mirror.orgStatus()) && mirror.loaded());
    }

    /** Fills every missing key of the tenant; never overwrites an existing one. */
    public Mono<Void> load(UUID orgId, String modelCode) {
        return Mono.fromCallable(() -> buildLoadArguments(orgId, modelCode))
                .subscribeOn(domainFallbackScheduler)
                .flatMap(args -> {
                    List<String> keys = List.of(
                            GatewayRedisKeys.wallet(orgId),
                            GatewayRedisKeys.pack(orgId, modelCode),
                            GatewayRedisKeys.bonus(orgId));
                    return redisTemplate.execute(loadScript, keys, args).then();
                });
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
        Wallet wallet = walletRepository.findByOrgId(orgId).orElse(null);
        Organization organization = organizationRepository.findById(orgId).orElse(null);
        String orgStatus = organization == null ? "ACTIVE" : organization.status().name();
        List<ResourcePack> packs = packRepository.listConsumableByOrgAndModel(orgId, modelCode, now);
        List<BonusGrant> grants = grantRepository.listConsumableByOrgAndModel(orgId, modelCode, now);

        List<String> args = new ArrayList<>();
        args.add(String.valueOf(properties.getCache().getSlidingTtlSeconds() * 1000L));
        args.add((wallet == null ? "0" : String.valueOf(wallet.cashBalanceMicros())) + "|0|" + orgStatus);
        args.add(String.valueOf(packs.size()));
        for (ResourcePack pack : packs) {
            args.add(pack.id() + "|" + pack.remainingUnits() + "|0|" + pack.unitType() + "|"
                    + epochMillis(pack.expiresAt()));
        }
        long mirroredMicros = grants.stream().mapToLong(BonusGrant::remainingMicros).sum();
        long unmirrored = wallet == null ? 0L : Math.max(0L, wallet.bonusBalanceMicros() - mirroredMicros);

        List<String> grantArgs = new ArrayList<>();
        for (BonusGrant grant : grants) {
            grantArgs.add(grant.id() + "|" + grant.remainingMicros() + "|0|"
                    + epochMillis(grant.expiresAt()));
        }
        if (unmirrored > 0L) {
            // Balance granted before batches existed: carried as one synthetic batch so it stays
            // spendable and is recognisable on the settlement side.
            grantArgs.add(app.pagefault.hyperllm.common.billing.LegacyBonusBatch.idFor(orgId)
                    + "|" + unmirrored + "|0|"
                    + app.pagefault.hyperllm.common.billing.LegacyBonusBatch.NO_EXPIRY_EPOCH_MS);
        }
        args.add(String.valueOf(grantArgs.size()));
        args.addAll(grantArgs);
        args.add(GatewayRedisKeys.packItemPrefix());
        args.add(GatewayRedisKeys.bonusItemPrefix());
        return args;
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

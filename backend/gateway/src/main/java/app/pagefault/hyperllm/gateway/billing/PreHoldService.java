package app.pagefault.hyperllm.gateway.billing;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pre-hold lifecycle on top of five atomic Lua scripts: admit, renew, settle, release, reclaim.
 *
 * <p>Reservations are tiered. Pack units are counted per (organisation, model), bonus and cash are
 * counted on the wallet hash, and "available" always means remaining minus what other in-flight
 * requests have reserved. Release paths never write a ledger row: no money has moved yet.
 */
@Service
public class PreHoldService {

    public record AdmitCommand(
            String holdId,
            UUID orgId,
            UUID projectId,
            UUID apiKeyId,
            String modelCode,
            long cashMicros,
            long bonusMicros,
            long packUnits,
            Integer keyRpmLimit,
            Integer projectRpmLimit,
            Integer keyMaxConcurrency,
            Integer projectMaxConcurrency,
            int orgMaxConcurrency,
            long ttlSeconds
    ) {
    }

    public record SettleOutcome(
            long cashCharged,
            long bonusCharged,
            boolean alreadySettled,
            boolean settledAfterReclaim
    ) {
    }

    private final ReactiveStringRedisTemplate redisTemplate;
    private final GatewayProperties properties;
    private final RedisScript<String> admitScript;
    private final RedisScript<Long> renewScript;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> settleScript;
    private final RedisScript<String> releaseScript;
    private final RedisScript<String> reclaimScript;

    @SuppressWarnings("rawtypes")
    public PreHoldService(
            ReactiveStringRedisTemplate redisTemplate,
            GatewayProperties properties,
            @Qualifier("preholdAdmitLuaScript") RedisScript<String> admitScript,
            @Qualifier("preholdRenewLuaScript") RedisScript<Long> renewScript,
            @Qualifier("preholdSettleLuaScript") RedisScript<List> settleScript,
            @Qualifier("preholdReleaseLuaScript") RedisScript<String> releaseScript,
            @Qualifier("preholdReclaimLuaScript") RedisScript<String> reclaimScript
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.admitScript = admitScript;
        this.renewScript = renewScript;
        this.settleScript = settleScript;
        this.releaseScript = releaseScript;
        this.reclaimScript = reclaimScript;
    }

    /** @return the script verdict: {@code OK} or a rejection reason the caller maps to an error. */
    public Mono<String> admit(AdmitCommand command) {
        long now = System.currentTimeMillis();
        long ttlMs = command.ttlSeconds() * 1000L;
        long epochMinute = Instant.now().getEpochSecond() / 60L;
        UUID effectiveKeyId = command.apiKeyId() != null ? command.apiKeyId() : command.projectId();

        List<String> keys = List.of(
                GatewayRedisKeys.wallet(command.orgId()),
                GatewayRedisKeys.packAvailability(command.orgId(), command.modelCode()),
                GatewayRedisKeys.packHeld(command.orgId(), command.modelCode()),
                GatewayRedisKeys.hold(command.holdId()),
                GatewayRedisKeys.holdsExpiry(),
                GatewayRedisKeys.orgHolds(command.orgId()),
                GatewayRedisKeys.rpmCounter("key", effectiveKeyId, epochMinute),
                GatewayRedisKeys.rpmCounter("proj", command.projectId(), epochMinute),
                GatewayRedisKeys.concurrencyCounter("key", effectiveKeyId),
                GatewayRedisKeys.concurrencyCounter("proj", command.projectId()),
                GatewayRedisKeys.concurrencyCounter("org", command.orgId())
        );
        List<String> args = List.of(
                command.holdId(),
                String.valueOf(now),
                String.valueOf(ttlMs),
                String.valueOf(command.cashMicros()),
                String.valueOf(command.bonusMicros()),
                String.valueOf(command.packUnits()),
                limit(command.keyRpmLimit()),
                limit(command.projectRpmLimit()),
                limit(command.keyMaxConcurrency()),
                limit(command.projectMaxConcurrency()),
                String.valueOf(command.orgMaxConcurrency()),
                command.orgId().toString(),
                command.projectId().toString(),
                command.apiKeyId() == null ? "" : command.apiKeyId().toString(),
                command.modelCode(),
                Instant.ofEpochMilli(now).toString()
        );
        return redisTemplate.execute(admitScript, keys, args)
                .next()
                .defaultIfEmpty("REJECTED")
                .map(String::valueOf);
    }

    /** @return true when the lease is still alive and was renewed. */
    public Mono<Boolean> renew(String holdId, UUID orgId, UUID projectId, UUID apiKeyId, long ttlSeconds) {
        long newExpiry = System.currentTimeMillis() + ttlSeconds * 1000L;
        UUID effectiveKeyId = apiKeyId != null ? apiKeyId : projectId;
        List<String> keys = List.of(
                GatewayRedisKeys.hold(holdId),
                GatewayRedisKeys.holdsExpiry(),
                GatewayRedisKeys.concurrencyCounter("key", effectiveKeyId),
                GatewayRedisKeys.concurrencyCounter("proj", projectId),
                GatewayRedisKeys.concurrencyCounter("org", orgId)
        );
        return redisTemplate.execute(
                        renewScript, keys,
                        List.of(holdId, String.valueOf(newExpiry), String.valueOf(ttlSeconds)))
                .next()
                .map(value -> value != null && value > 0L)
                .defaultIfEmpty(false);
    }

    public Mono<SettleOutcome> settle(
            String holdId,
            UUID orgId,
            String modelCode,
            long cashToCharge,
            long bonusToCharge,
            long packUnitsConsumed
    ) {
        List<String> keys = List.of(
                GatewayRedisKeys.wallet(orgId),
                GatewayRedisKeys.hold(holdId),
                GatewayRedisKeys.holdDone(holdId),
                GatewayRedisKeys.holdsExpiry(),
                GatewayRedisKeys.orgHolds(orgId),
                GatewayRedisKeys.packHeld(orgId, modelCode)
        );
        List<String> args = List.of(
                holdId,
                String.valueOf(Math.max(0L, cashToCharge)),
                String.valueOf(Math.max(0L, bonusToCharge)),
                String.valueOf(Math.max(0L, packUnitsConsumed)),
                String.valueOf(properties.getBilling().getLeaseTtlSeconds() * 12L)
        );
        return redisTemplate.execute(settleScript, keys, args)
                .next()
                .map(raw -> {
                    String status = String.valueOf(raw.get(0));
                    if ("ALREADY_SETTLED".equals(status)) {
                        return new SettleOutcome(0L, 0L, true, false);
                    }
                    return new SettleOutcome(
                            asLong(raw.get(1)), asLong(raw.get(2)), false, asLong(raw.get(3)) == 1L);
                })
                .defaultIfEmpty(new SettleOutcome(0L, 0L, false, false));
    }

    public Mono<Boolean> release(String holdId, UUID orgId) {
        return redisTemplate.opsForHash()
                .get(GatewayRedisKeys.hold(holdId), "model_code")
                .map(String::valueOf)
                .defaultIfEmpty("")
                .flatMap(modelCode -> redisTemplate.execute(
                                releaseScript,
                                List.of(
                                        GatewayRedisKeys.wallet(orgId),
                                        GatewayRedisKeys.hold(holdId),
                                        GatewayRedisKeys.holdDone(holdId),
                                        GatewayRedisKeys.holdsExpiry(),
                                        GatewayRedisKeys.orgHolds(orgId),
                                        GatewayRedisKeys.packHeld(orgId, modelCode)),
                                List.of(holdId, String.valueOf(properties.getBilling().getLeaseTtlSeconds() * 12L)))
                        .hasElements());
    }

    /**
     * Reclaims expired holds of one organisation, most expired first. The script itself decides
     * whether a candidate is really expired, so a concurrent heartbeat wins the race safely.
     */
    public Mono<Integer> reclaimExpired(UUID orgId) {
        int batch = properties.getBilling().getLazyReclaimBatch();
        return redisTemplate.opsForSet().members(GatewayRedisKeys.orgHolds(orgId))
                .take(batch)
                .flatMap(holdId -> reclaimOne(orgId, holdId))
                .reduce(0, Integer::sum);
    }

    private Mono<Integer> reclaimOne(UUID orgId, String holdId) {
        return redisTemplate.opsForZSet()
                .score(GatewayRedisKeys.holdsExpiry(), holdId)
                .flatMap(score -> {
                    if (score == null || score > System.currentTimeMillis()) {
                        return Mono.just(0);
                    }
                    return redisTemplate.opsForHash()
                            .get(GatewayRedisKeys.hold(holdId), "model_code")
                            .map(String::valueOf)
                            .defaultIfEmpty("")
                            .flatMap(modelCode -> redisTemplate.execute(
                                            reclaimScript,
                                            List.of(
                                                    GatewayRedisKeys.hold(holdId),
                                                    GatewayRedisKeys.holdsExpiry(),
                                                    GatewayRedisKeys.orgHolds(orgId),
                                                    GatewayRedisKeys.wallet(orgId),
                                                    GatewayRedisKeys.packHeld(orgId, modelCode)),
                                            List.of(holdId, String.valueOf(System.currentTimeMillis())))
                                    .next()
                                    .map(result -> "RELEASED".equals(String.valueOf(result)) ? 1 : 0)
                                    .defaultIfEmpty(0));
                })
                .defaultIfEmpty(0);
    }

    private static String limit(Integer value) {
        return (value != null && value > 0) ? String.valueOf(value) : "0";
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
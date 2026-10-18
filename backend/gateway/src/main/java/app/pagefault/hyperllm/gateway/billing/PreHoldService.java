package app.pagefault.hyperllm.gateway.billing;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.domain.billing.AllocationLeg;
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
import java.util.stream.Collectors;

/**
 * Pre-hold lifecycle on top of six atomic Lua scripts.
 *
 * <p>Reservations are frozen per batch, not in aggregate: a request reserves exactly the batches the
 * allocator picked, and the settlement later charges those same batches. That is what makes the
 * reservation exact - two requests can never be handed the same remainder of one batch.
 *
 * <p>Every script derives the batch keys it touches from a prefix instead of taking them as KEYS,
 * because the settlement does not know which batches the hold froze until it reads the hold. The
 * billing cluster is therefore addressed as whole instances per tenant shard, never as a cluster
 * that would need every key declared up front.
 */
@Service
public class PreHoldService {

    /** One request's reservation, expressed as the legs the allocator produced. */
    public record AdmitCommand(
            String holdId,
            UUID orgId,
            UUID projectId,
            UUID apiKeyId,
            String modelCode,
            List<AllocationLeg> legs,
            Integer keyRpmLimit,
            Integer projectRpmLimit,
            Integer keyMaxConcurrency,
            Integer projectMaxConcurrency,
            int orgMaxConcurrency,
            long ttlSeconds,
            boolean renewKeys
    ) {
    }

    public record SettleOutcome(
            long cashCharged,
            long bonusCharged,
            boolean alreadySettled,
            boolean settledAfterReclaim,
            boolean missingEntitlements
    ) {
    }

    private static final String MISS_ENTITLEMENTS = "MISS_ENTITLEMENTS";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final GatewayProperties properties;
    private final RedisScript<String> admitScript;
    private final RedisScript<Long> renewScript;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> settleScript;
    private final RedisScript<String> releaseScript;
    private final RedisScript<String> reclaimScript;
    private final RedisScript<Long> servedScript;

    @SuppressWarnings("rawtypes")
    public PreHoldService(
            ReactiveStringRedisTemplate redisTemplate,
            GatewayProperties properties,
            @Qualifier("preholdAdmitLuaScript") RedisScript<String> admitScript,
            @Qualifier("preholdRenewLuaScript") RedisScript<Long> renewScript,
            @Qualifier("preholdSettleLuaScript") RedisScript<List> settleScript,
            @Qualifier("preholdReleaseLuaScript") RedisScript<String> releaseScript,
            @Qualifier("preholdReclaimLuaScript") RedisScript<String> reclaimScript,
            @Qualifier("preholdServedLuaScript") RedisScript<Long> servedScript
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.admitScript = admitScript;
        this.renewScript = renewScript;
        this.settleScript = settleScript;
        this.releaseScript = releaseScript;
        this.reclaimScript = reclaimScript;
        this.servedScript = servedScript;
    }

    /** @return the script verdict: {@code OK} or a rejection reason the caller maps to an error. */
    public Mono<String> admit(AdmitCommand command) {
        long now = System.currentTimeMillis();
        long ttlMillis = command.ttlSeconds() * 1000L;
        long epochMinute = Instant.now().getEpochSecond() / 60L;
        UUID effectiveKeyId = command.apiKeyId() != null ? command.apiKeyId() : command.projectId();

        List<String> keys = List.of(
                GatewayRedisKeys.wallet(command.orgId()),
                GatewayRedisKeys.ledgerPosition(command.orgId()),
                GatewayRedisKeys.hold(command.holdId()),
                GatewayRedisKeys.holdsExpiry(),
                GatewayRedisKeys.rpmCounter("key", effectiveKeyId, epochMinute),
                GatewayRedisKeys.rpmCounter("proj", command.projectId(), epochMinute),
                GatewayRedisKeys.concurrencyCounter("key", effectiveKeyId),
                GatewayRedisKeys.concurrencyCounter("proj", command.projectId()),
                GatewayRedisKeys.concurrencyCounter("org", command.orgId()));
        List<String> args = List.of(
                command.holdId(),
                String.valueOf(now),
                String.valueOf(ttlMillis),
                encodeLegs(command.legs()),
                command.renewKeys() ? "1" : "0",
                command.orgId().toString(),
                command.projectId().toString(),
                command.apiKeyId() == null ? "" : command.apiKeyId().toString(),
                command.modelCode(),
                Instant.ofEpochMilli(now).toString(),
                GatewayRedisKeys.packItemPrefix(),
                GatewayRedisKeys.bonusItemPrefix(),
                limit(command.keyRpmLimit()),
                limit(command.projectRpmLimit()),
                limit(command.keyMaxConcurrency()),
                limit(command.projectMaxConcurrency()),
                String.valueOf(command.orgMaxConcurrency()));
        return redisTemplate.execute(admitScript, keys, args)
                .next()
                .defaultIfEmpty("REJECTED")
                .map(String::valueOf);
    }

    public Mono<SettleOutcome> settle(
            String holdId,
            UUID orgId,
            List<AllocationLeg> charges,
            String eventPayload,
            long doneTtlSeconds,
            long payloadTtlMillis,
            boolean renewKeys,
            long ttlSeconds
    ) {
        long cash = sumMicros(charges, "CASH");
        long bonus = sumMicros(charges, "BONUS");
        long units = charges.stream()
                .filter(leg -> "PACK".equals(leg.type().name()))
                .mapToLong(AllocationLeg::units)
                .sum();
        List<String> keys = List.of(
                GatewayRedisKeys.wallet(orgId),
                GatewayRedisKeys.hold(holdId),
                GatewayRedisKeys.holdDone(holdId),
                GatewayRedisKeys.holdsExpiry(),
                GatewayRedisKeys.settleEvent(holdId),
                GatewayRedisKeys.settlePending());
        List<String> args = List.of(
                holdId,
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(cash),
                String.valueOf(bonus),
                String.valueOf(units),
                encodeLegs(charges),
                eventPayload == null ? "{}" : eventPayload,
                String.valueOf(doneTtlSeconds),
                String.valueOf(payloadTtlMillis),
                GatewayRedisKeys.packItemPrefix(),
                GatewayRedisKeys.bonusItemPrefix(),
                renewKeys ? "1" : "0",
                String.valueOf(ttlSeconds * 1000L));
        return redisTemplate.execute(settleScript, keys, args)
                .next()
                .map(raw -> {
                    String status = String.valueOf(raw.get(0));
                    if ("ALREADY_SETTLED".equals(status)) {
                        return new SettleOutcome(0L, 0L, true, false, false);
                    }
                    if (MISS_ENTITLEMENTS.equals(status)) {
                        return new SettleOutcome(0L, 0L, false, false, true);
                    }
                    return new SettleOutcome(
                            asLong(raw.get(1)), asLong(raw.get(2)), false, asLong(raw.get(3)) == 1L, false);
                })
                .defaultIfEmpty(new SettleOutcome(0L, 0L, false, false, false));
    }

    public Mono<Boolean> release(String holdId, UUID orgId, long doneTtlSeconds) {
        List<String> keys = List.of(
                GatewayRedisKeys.wallet(orgId),
                GatewayRedisKeys.hold(holdId),
                GatewayRedisKeys.holdDone(holdId),
                GatewayRedisKeys.holdsExpiry());
        return redisTemplate.execute(releaseScript, keys, List.of(
                        holdId,
                        GatewayRedisKeys.packItemPrefix(),
                        GatewayRedisKeys.bonusItemPrefix(),
                        String.valueOf(doneTtlSeconds)))
                .hasElements();
    }

    /** @return true when the lease is still alive and was renewed. */
    public Mono<Boolean> renew(
            String holdId,
            UUID orgId,
            List<String> concurrencyKeys,
            long ttlSeconds,
            boolean renewKeys
    ) {
        long ttlMillis = ttlSeconds * 1000L;
        List<String> keys = List.of(
                GatewayRedisKeys.hold(holdId),
                GatewayRedisKeys.holdsExpiry(),
                GatewayRedisKeys.wallet(orgId),
                concurrencyKeys.size() > 0 ? concurrencyKeys.get(0) : GatewayRedisKeys.hold(holdId),
                concurrencyKeys.size() > 1 ? concurrencyKeys.get(1) : GatewayRedisKeys.hold(holdId),
                concurrencyKeys.size() > 2 ? concurrencyKeys.get(2) : GatewayRedisKeys.hold(holdId));
        return redisTemplate.execute(renewScript, keys, List.of(
                        holdId,
                        String.valueOf(System.currentTimeMillis() + ttlMillis),
                        String.valueOf(ttlMillis),
                        renewKeys ? "1" : "0",
                        GatewayRedisKeys.packItemPrefix(),
                        GatewayRedisKeys.bonusItemPrefix()))
                .next()
                .map(value -> value != null && value > 0L)
                .defaultIfEmpty(false);
    }

    /** Records that the client has received its first byte, which legitimises a floor settlement. */
    public Mono<Boolean> markServed(String holdId, long ttlSeconds) {
        return redisTemplate.execute(servedScript, List.of(GatewayRedisKeys.hold(holdId)),
                        List.of(String.valueOf(ttlSeconds * 1000L)))
                .next()
                .map(value -> value != null && value > 0L)
                .defaultIfEmpty(false);
    }

    /**
     * Reclaims expired holds across every organisation. The expiry index is the only scan entry
     * point; the script re-checks the score before releasing anything, so a heartbeat that lands
     * first wins the race safely.
     */
    public Mono<Integer> reclaimExpired(int batch) {
        return redisTemplate.opsForZSet()
                .rangeByScore(
                        GatewayRedisKeys.holdsExpiry(),
                        org.springframework.data.domain.Range.closed(0d, (double) System.currentTimeMillis()),
                        org.springframework.data.redis.connection.Limit.limit().count(batch))
                .flatMap(holdId -> redisTemplate.opsForHash()
                        .get(GatewayRedisKeys.hold(holdId), "org_id")
                        .map(String::valueOf)
                        .flatMap(orgId -> reclaimOne(UUID.fromString(orgId), holdId))
                        .defaultIfEmpty(0))
                .reduce(0, Integer::sum);
    }

    private Mono<Integer> reclaimOne(UUID orgId, String holdId) {
        List<String> keys = List.of(
                GatewayRedisKeys.hold(holdId),
                GatewayRedisKeys.holdsExpiry(),
                GatewayRedisKeys.wallet(orgId));
        return redisTemplate.execute(reclaimScript, keys, List.of(
                        holdId,
                        String.valueOf(System.currentTimeMillis()),
                        GatewayRedisKeys.packItemPrefix(),
                        GatewayRedisKeys.bonusItemPrefix()))
                .next()
                .map(result -> "RELEASED".equals(String.valueOf(result)) ? 1 : 0)
                .defaultIfEmpty(0);
    }

    static String encodeLegs(List<AllocationLeg> legs) {
        return legs.stream()
                .map(leg -> leg.type().name()
                        + "|" + (leg.id() == null ? "" : leg.id())
                        + "|" + leg.units()
                        + "|" + leg.micros())
                .collect(Collectors.joining(";"));
    }

    private static long sumMicros(List<AllocationLeg> legs, String type) {
        return legs.stream()
                .filter(leg -> type.equals(leg.type().name()))
                .mapToLong(AllocationLeg::micros)
                .sum();
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

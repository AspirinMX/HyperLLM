package app.pagefault.hyperllm.gateway.billing;

import app.pagefault.hyperllm.common.billing.LegacyBonusBatch;
import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.domain.billing.AllocationLeg;
import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import app.pagefault.hyperllm.gateway.outbox.OutboxQueue;
import app.pagefault.hyperllm.gateway.outbox.PendingMoneyEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Owns the reservation and all of its terminal transitions. */
@Slf4j
@Service
public class PreHoldService {

    private static final long EVIDENCE_TTL_MS = Duration.ofHours(1).toMillis();
    private static final UUID NO_ORG = new UUID(0L, 0L);

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
            String requestId,
            List<AllocationLeg> fallbackLegs,
            String fallbackDetail
    ) {
    }

    public record Lease(UUID orgId, List<String> concurrencyKeys, String holdId) {
    }

    public record SettleOutcome(String status, long cashCharged, long bonusCharged, long shortfallMicros) {
        public boolean alreadySettled() {
            return "ALREADY_SETTLED".equals(status);
        }
    }

    private enum Transition { SETTLE, RELEASE, RECLAIM }

    private final ReactiveStringRedisTemplate redisTemplate;
    private final long doneTtlMillis;
    private final RedisScript<String> admitScript;
    private final RedisScript<Long> renewScript;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> settleScript;
    private final RedisScript<Long> servedScript;
    private final OutboxQueue outboxQueue;

    @SuppressWarnings("rawtypes")
    public PreHoldService(
            ReactiveStringRedisTemplate redisTemplate,
            GatewayProperties properties,
            @Qualifier("preholdAdmitLuaScript") RedisScript<String> admitScript,
            @Qualifier("preholdRenewLuaScript") RedisScript<Long> renewScript,
            @Qualifier("preholdSettleLuaScript") RedisScript<List> settleScript,
            @Qualifier("preholdServedLuaScript") RedisScript<Long> servedScript,
            OutboxQueue outboxQueue
    ) {
        this.redisTemplate = redisTemplate;
        this.doneTtlMillis = Duration.ofSeconds(properties.getBilling().getLeaseTtlSeconds()).toMillis() * 12L;
        this.admitScript = admitScript;
        this.renewScript = renewScript;
        this.settleScript = settleScript;
        this.servedScript = servedScript;
        this.outboxQueue = outboxQueue;
    }

    public Mono<String> admit(AdmitCommand command) {
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
        long now = at.toInstant().toEpochMilli();
        long ttlMillis = Duration.ofSeconds(command.ttlSeconds()).toMillis();
        long epochMinute = at.toEpochSecond() / 60L;
        UUID effectiveKeyId = command.apiKeyId() != null ? command.apiKeyId() : command.projectId();
        List<String> keys = List.of(
                GatewayRedisKeys.wallet(command.orgId()), GatewayRedisKeys.holdDone(command.holdId()),
                GatewayRedisKeys.hold(command.holdId()), GatewayRedisKeys.holdsExpiry(),
                GatewayRedisKeys.rpmCounter("key", effectiveKeyId, epochMinute),
                GatewayRedisKeys.rpmCounter("proj", command.projectId(), epochMinute),
                GatewayRedisKeys.concurrencyCounter("key", effectiveKeyId),
                GatewayRedisKeys.concurrencyCounter("proj", command.projectId()),
                GatewayRedisKeys.concurrencyCounter("org", command.orgId()));
        List<String> args = List.of(
                command.holdId(), Long.toString(now), Long.toString(ttlMillis), encodeLegs(command.legs()),
                "0", command.orgId().toString(), command.projectId().toString(),
                command.apiKeyId() == null ? "" : command.apiKeyId().toString(), command.modelCode(), at.toString(),
                GatewayRedisKeys.packItemPrefix(), GatewayRedisKeys.bonusItemPrefix(),
                limit(command.keyRpmLimit()), limit(command.projectRpmLimit()), limit(command.keyMaxConcurrency()),
                limit(command.projectMaxConcurrency()), limit(command.orgMaxConcurrency()),
                command.requestId(), encodeLegs(command.fallbackLegs()), command.fallbackDetail());
        return redisTemplate.execute(admitScript, keys, args).next().defaultIfEmpty("REJECTED");
    }

    public Mono<SettleOutcome> settle(
            Lease lease, List<AllocationLeg> charges, String detailPrefix, OffsetDateTime occurredAt
    ) {
        return transition(lease.holdId(), lease.orgId(), Transition.SETTLE, charges, detailPrefix, occurredAt);
    }

    public Mono<Void> release(Lease lease) {
        return transition(lease.holdId(), lease.orgId(), Transition.RELEASE, List.of(), "", utcNow()).then();
    }

    private Mono<SettleOutcome> transition(
            String holdId, UUID orgId, Transition mode, List<AllocationLeg> charges, String detailPrefix,
            OffsetDateTime occurredAt
    ) {
        List<String> keys = List.of(
                GatewayRedisKeys.wallet(orgId), GatewayRedisKeys.hold(holdId), GatewayRedisKeys.holdDone(holdId),
                GatewayRedisKeys.holdsExpiry(), GatewayRedisKeys.settleEvent(holdId), GatewayRedisKeys.settlePending(),
                GatewayRedisKeys.ledgerPosition(orgId), GatewayRedisKeys.settlePendingOwners(),
                GatewayRedisKeys.settleProof(holdId), GatewayRedisKeys.settleWaiting(orgId));
        List<String> args = List.of(
                holdId, Long.toString(occurredAt.toInstant().toEpochMilli()), occurredAt.toString(), mode.name(),
                encodeLegs(charges), detailPrefix, Long.toString(doneTtlMillis), Long.toString(EVIDENCE_TTL_MS),
                GatewayRedisKeys.packItemPrefix(), GatewayRedisKeys.bonusItemPrefix(), LegacyBonusBatch.idFor(orgId).toString());
        return redisTemplate.execute(settleScript, keys, args).next().map(raw -> {
            String status = String.valueOf(raw.getFirst());
            if (raw.size() == 1) {
                if ("INVALID_CHARGE".equals(status)) {
                    throw new IllegalStateException("Settlement exceeds reservation for hold " + holdId);
                }
                return new SettleOutcome(status, 0L, 0L, 0L);
            }
            if ("SETTLED".equals(status) && !outboxQueue.offer(new PendingMoneyEvent(
                    UUID.fromString(holdId), orgId.toString(), "CONSUME", String.valueOf(raw.get(4)), occurredAt))) {
                log.warn("Outbox queue full, leaving settlement {} for replay", holdId);
            }
            return new SettleOutcome(status, number(raw.get(1)), number(raw.get(2)), number(raw.get(3)));
        }).switchIfEmpty(Mono.error(new IllegalStateException("Redis returned no settlement verdict")));
    }

    public Mono<Boolean> renew(Lease lease, long ttlSeconds) {
        long ttlMillis = Duration.ofSeconds(ttlSeconds).toMillis();
        String hold = GatewayRedisKeys.hold(lease.holdId());
        List<String> counters = lease.concurrencyKeys();
        List<String> keys = List.of(hold, GatewayRedisKeys.holdsExpiry(), GatewayRedisKeys.wallet(lease.orgId()),
                counters.size() > 0 ? counters.get(0) : hold,
                counters.size() > 1 ? counters.get(1) : hold,
                counters.size() > 2 ? counters.get(2) : hold);
        return redisTemplate.execute(renewScript, keys, List.of(lease.holdId(),
                        Long.toString(System.currentTimeMillis() + ttlMillis), Long.toString(ttlMillis), "0",
                        GatewayRedisKeys.packItemPrefix(), GatewayRedisKeys.bonusItemPrefix()))
                .next().map(value -> value > 0L).defaultIfEmpty(false);
    }

    public Mono<Void> markServed(Lease lease) {
        return redisTemplate.execute(servedScript, List.of(GatewayRedisKeys.hold(lease.holdId())), List.of()).then();
    }

    /** Rechecks the expiry score inside the terminal transition, after any concurrent heartbeat. */
    public Mono<Integer> reclaimExpired(int batch) {
        return redisTemplate.opsForZSet()
                .rangeByScore(GatewayRedisKeys.holdsExpiry(), Range.closed(0d, (double) System.currentTimeMillis()),
                        Limit.limit().count(batch))
                .flatMap(holdId -> redisTemplate.opsForHash().get(GatewayRedisKeys.hold(holdId), "org_id")
                        .map(value -> UUID.fromString(String.valueOf(value)))
                        .defaultIfEmpty(NO_ORG)
                        .flatMap(orgId -> transition(holdId, orgId, Transition.RECLAIM, List.of(), "", utcNow()))
                        .map(outcome -> switch (outcome.status()) {
                            case "SETTLED", "RELEASED", "MISS_ENTITLEMENTS" -> 1;
                            default -> 0;
                        }), 16)
                .reduce(0, Integer::sum);
    }

    static String encodeLegs(List<AllocationLeg> legs) {
        return legs.stream()
                .map(leg -> leg.type().name() + "|" + (leg.id() == null ? "" : leg.id())
                        + "|" + leg.units() + "|" + leg.micros())
                .collect(Collectors.joining(";"));
    }

    private static OffsetDateTime utcNow() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static String limit(Integer value) {
        return value != null && value > 0 ? value.toString() : "0";
    }

    private static long number(Object value) {
        return Long.parseLong(String.valueOf(value));
    }
}

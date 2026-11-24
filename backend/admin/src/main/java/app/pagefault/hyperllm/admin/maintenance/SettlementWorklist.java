package app.pagefault.hyperllm.admin.maintenance;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.common.cache.LuaScripts;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Redis evidence and scheduling for settlements awaiting confirmation. */
@Component
@RequiredArgsConstructor
public class SettlementWorklist {

    private static final RedisScript<Long> UPDATE =
            LuaScripts.loadClasspath("lua/settlement_worklist.lua", Long.class);
    private static final RedisScript<Long> CHECK_PROOF =
            LuaScripts.loadClasspath("lua/settlement_check_proof.lua", Long.class);

    private static final RedisScript<Long> FENCE_SNAPSHOT =
            LuaScripts.loadClasspath("lua/settlement_fence_snapshot.lua", Long.class);

    private final StringRedisTemplate redis;

    record Owner(UUID orgId, OffsetDateTime occurredAt) {
    }

    Set<String> due(OffsetDateTime cutoff, int limit) {
        return redis.opsForZSet().rangeByScore(GatewayRedisKeys.settlePending(), 0,
                cutoff.toInstant().toEpochMilli(), 0, limit);
    }

    Owner owner(String id) {
        Object value = redis.opsForHash().get(GatewayRedisKeys.settlePendingOwners(), id);
        if (value == null) {
            return null;
        }
        String[] parts = value.toString().split("\\|", 2);
        return new Owner(UUID.fromString(parts[0]), OffsetDateTime.parse(parts[1]));
    }

    String payload(String id) {
        return redis.opsForValue().get(GatewayRedisKeys.settleEvent(id));
    }

    boolean provesLiveDebit(String id) {
        return Long.valueOf(1L).equals(redis.execute(CHECK_PROOF, List.of(GatewayRedisKeys.settleProof(id))));
    }

    void defer(String id, OffsetDateTime at) {
        update(id, Long.toString(at.toInstant().toEpochMilli()));
    }

    /** Publishes the minimum checkpoint version before committing a tracked debit. */
    public void fenceSnapshot(UUID orgId, UUID id, long version) {
        redis.execute(FENCE_SNAPSHOT, List.of(GatewayRedisKeys.settleWaiting(orgId),
                GatewayRedisKeys.ledgerPosition(orgId)), id.toString(), Long.toString(version));
    }

    public void resolve(String id) {
        update(id, "");
    }

    private void update(String id, String next) {
        redis.execute(UPDATE, List.of(GatewayRedisKeys.settlePending(), GatewayRedisKeys.settlePendingOwners(),
                GatewayRedisKeys.settleEvent(id), GatewayRedisKeys.settleProof(id)), id, next,
                GatewayRedisKeys.settleWaitingPrefix());
    }
}

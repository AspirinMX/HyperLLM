package app.pagefault.hyperllm.gateway.billing;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Owns the concurrency leases that back one admitted request.
 *
 * <p>It does not deduct anything: money moves only through the pre-hold scripts, so there is exactly
 * one path that can change a balance. What remains here is the lease bookkeeping that admission and
 * release share.
 */
@Service
public class GatewayAdmissionService {

    /** Concurrency slots held for one request; empty when no limit is configured. */
    public record AdmissionLease(UUID orgId, List<String> concurrencyKeys, String holdId) {

        public static AdmissionLease empty(UUID orgId) {
            return new AdmissionLease(orgId, List.of(), null);
        }
    }

    private final ReactiveStringRedisTemplate redisTemplate;
    private final RedisScript<Long> releaseConcurrencyLuaScript;

    @SuppressWarnings("rawtypes")
    public GatewayAdmissionService(
            ReactiveStringRedisTemplate redisTemplate,
            @Qualifier("releaseConcurrencyLuaScript") RedisScript<Long> releaseConcurrencyLuaScript
    ) {
        this.redisTemplate = redisTemplate;
        this.releaseConcurrencyLuaScript = releaseConcurrencyLuaScript;
    }

    public Mono<Void> releaseLease(AdmissionLease lease) {
        if (lease == null || lease.concurrencyKeys().isEmpty()) {
            return Mono.empty();
        }
        return redisTemplate.execute(releaseConcurrencyLuaScript, lease.concurrencyKeys(), List.of())
                .then()
                .onErrorResume(ex -> Mono.empty());
    }
}

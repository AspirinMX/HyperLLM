package app.pagefault.hyperllm.gateway.billing;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.gateway.cache.GatewayConfigCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Executes atomic Redis Lua admission (wallet &gt; 0, RPM, concurrency leases) and post-response
 * simplified Phase 1 deduction (bonus balance first, then cash balance).
 */
@Service
@RequiredArgsConstructor
public class GatewayAdmissionService {

    public record AdmissionLease(UUID orgId, List<String> concurrencyKeys) {
        public static AdmissionLease empty(UUID orgId) {
            return new AdmissionLease(orgId, List.of());
        }
    }

    public record DeductionOutcome(long bonusDeductedMicros, long cashDeductedMicros) {
    }

    private final ReactiveStringRedisTemplate redisTemplate;
    private final GatewayConfigCacheService cacheService;
    private final RedisScript<String> admissionLuaScript;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> deductWalletLuaScript;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> deductWalletSplitLuaScript;
    private final RedisScript<Long> releaseConcurrencyLuaScript;

    public Mono<AdmissionLease> admit(
            UUID orgId,
            UUID projectId,
            UUID apiKeyId,
            Integer keyRpmLimit,
            Integer projRpmLimit,
            Integer keyMaxConcurrency,
            Integer projMaxConcurrency,
            int orgMaxConcurrency
    ) {
        long epochMinute = Instant.now().getEpochSecond() / 60L;
        UUID effectiveKeyId = apiKeyId != null ? apiKeyId : projectId;

        String walletKey = GatewayRedisKeys.wallet(orgId);
        String keyRpmKey = GatewayRedisKeys.rpmCounter("key", effectiveKeyId, epochMinute);
        String projRpmKey = GatewayRedisKeys.rpmCounter("proj", projectId, epochMinute);
        String keyConcKey = GatewayRedisKeys.concurrencyCounter("key", effectiveKeyId);
        String projConcKey = GatewayRedisKeys.concurrencyCounter("proj", projectId);
        String orgConcKey = GatewayRedisKeys.concurrencyCounter("org", orgId);

        List<String> keys = List.of(walletKey, keyRpmKey, projRpmKey, keyConcKey, projConcKey, orgConcKey);
        List<String> args = List.of(
                limitStr(keyRpmLimit),
                limitStr(projRpmLimit),
                limitStr(keyMaxConcurrency),
                limitStr(projMaxConcurrency),
                limitStr(orgMaxConcurrency)
        );

        List<String> acquiredConcKeys = new ArrayList<>(3);
        if (positive(keyMaxConcurrency)) {
            acquiredConcKeys.add(keyConcKey);
        }
        if (positive(projMaxConcurrency)) {
            acquiredConcKeys.add(projConcKey);
        }
        if (orgMaxConcurrency > 0) {
            acquiredConcKeys.add(orgConcKey);
        }

        return executeAdmission(keys, args)
                .flatMap(result -> {
                    if ("MISS_WALLET".equals(result)) {
                        return cacheService.warmWalletIfMissing(orgId)
                                .flatMap(warmed -> warmed
                                        ? executeAdmission(keys, args)
                                        : Mono.just("INSUFFICIENT_BALANCE"));
                    }
                    return Mono.just(result);
                })
                .flatMap(result -> switch (result) {
                    case "OK" -> Mono.just(new AdmissionLease(orgId, List.copyOf(acquiredConcKeys)));
                    case "ORG_SUSPENDED" -> Mono.error(new HyperLlmException(ErrorCode.TENANT_ORG_SUSPENDED));
                    case "INSUFFICIENT_BALANCE" -> Mono.error(new HyperLlmException(ErrorCode.BILLING_INSUFFICIENT_BALANCE));
                    case "RATE_LIMIT" -> Mono.error(new HyperLlmException(ErrorCode.RATE_LIMITED));
                    default -> Mono.error(new HyperLlmException(ErrorCode.BILLING_INSUFFICIENT_BALANCE));
                });
    }

    public Mono<Void> releaseLease(AdmissionLease lease) {
        if (lease == null || lease.concurrencyKeys().isEmpty()) {
            return Mono.empty();
        }
        return redisTemplate.execute(releaseConcurrencyLuaScript, lease.concurrencyKeys(), List.of())
                .then()
                .onErrorResume(ex -> Mono.empty());
    }

    public Mono<DeductionOutcome> deductCost(UUID orgId, long exactCostMicros) {
        if (exactCostMicros <= 0L) {
            return Mono.just(new DeductionOutcome(0L, 0L));
        }
        String walletKey = GatewayRedisKeys.wallet(orgId);
        return redisTemplate.execute(deductWalletLuaScript, List.of(walletKey), List.of(String.valueOf(exactCostMicros)))
                .next()
                .map(rawList -> {
                    long bonusDeducted = extractLong(rawList, 0);
                    long cashDeducted = extractLong(rawList, 1);
                    return new DeductionOutcome(bonusDeducted, cashDeducted);
                })
                .defaultIfEmpty(new DeductionOutcome(0L, 0L));
    }

    /**
     * Applies an allocation plan's cash and bonus legs exactly as computed, rather than letting the
     * wallet decide the split. Both buckets are clamped inside the script, so a stale snapshot
     * cannot drive the balance negative.
     */
    public Mono<DeductionOutcome> deductExact(UUID orgId, long cashMicros, long bonusMicros) {
        if (cashMicros <= 0L && bonusMicros <= 0L) {
            return Mono.just(new DeductionOutcome(0L, 0L));
        }
        String walletKey = GatewayRedisKeys.wallet(orgId);
        return redisTemplate.execute(
                        deductWalletSplitLuaScript,
                        List.of(walletKey),
                        List.of(String.valueOf(Math.max(cashMicros, 0L)), String.valueOf(Math.max(bonusMicros, 0L))))
                .next()
                .map(rawList -> {
                    long bonusApplied = extractLong(rawList, 0);
                    long cashApplied = extractLong(rawList, 1);
                    return new DeductionOutcome(bonusApplied, cashApplied);
                })
                .defaultIfEmpty(new DeductionOutcome(0L, 0L));
    }

    private Mono<String> executeAdmission(List<String> keys, List<String> args) {
        return redisTemplate.execute(admissionLuaScript, keys, args)
                .next()
                .defaultIfEmpty("INSUFFICIENT_BALANCE");
    }

    private static String limitStr(Integer limit) {
        return (limit != null && limit > 0) ? String.valueOf(limit) : "0";
    }

    private static boolean positive(Integer limit) {
        return limit != null && limit > 0;
    }

    private static long extractLong(List<?> list, int index) {
        if (list == null || index >= list.size() || list.get(index) == null) {
            return 0L;
        }
        Object val = list.get(index);
        if (val instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(val.toString());
    }
}

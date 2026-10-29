package app.pagefault.hyperllm.console.config;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.common.cache.SandboxTokenPayload;
import app.pagefault.hyperllm.domain.model.tenant.GatewayKeyContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Synchronizes console mutations into Redis L2 keys after transaction commits.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsoleCacheSyncService {

    private static final Duration KEY_CFG_TTL = Duration.ofMinutes(30);
    private static final Duration OTP_TTL = Duration.ofMinutes(10);
    private static final Duration OTP_COOLDOWN_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AfterCommitRunner afterCommitRunner;

    public boolean tryAcquireOtpCooldown(String purpose, String email) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(GatewayRedisKeys.otpCooldown(purpose, email), "1", OTP_COOLDOWN_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    public void storeOtpCode(String purpose, String email, String code) {
        redisTemplate.opsForValue().set(GatewayRedisKeys.otpCode(purpose, email), code, OTP_TTL);
    }

    public boolean verifyAndConsumeOtp(String purpose, String email, String submittedCode) {
        String key = GatewayRedisKeys.otpCode(purpose, email);
        String expected = redisTemplate.opsForValue().get(key);
        if (expected == null || !expected.equals(submittedCode)) {
            return false;
        }
        redisTemplate.delete(key);
        return true;
    }

    public void storeSandboxToken(String sha256Hash, SandboxTokenPayload payload, Duration ttl) {
        String json = toJson(payload);
        redisTemplate.opsForValue().set(GatewayRedisKeys.sandboxToken(sha256Hash), json, ttl);
    }

    public void warmKeyContextAfterCommit(Optional<GatewayKeyContext> contextOpt) {
        if (contextOpt.isEmpty()) {
            return;
        }
        GatewayKeyContext ctx = contextOpt.get();
        afterCommitRunner.runAfterCommit(() -> {
            try {
                redisTemplate.opsForValue().set(GatewayRedisKeys.keyConfig(ctx.keyHash()), toJson(ctx), KEY_CFG_TTL);
            } catch (Exception ex) {
                log.warn("Failed to warm Redis key context for hash prefix {}", ctx.keyPrefix(), ex);
            }
        });
    }

    public void evictKeyHashesAfterCommit(Collection<String> keyHashes) {
        if (keyHashes == null || keyHashes.isEmpty()) {
            return;
        }
        var keys = keyHashes.stream().map(GatewayRedisKeys::keyConfig).toList();
        afterCommitRunner.runAfterCommit(() -> {
            try {
                redisTemplate.delete(keys);
            } catch (Exception ex) {
                log.warn("Failed to evict {} Redis API key cache entries", keys.size(), ex);
            }
        });
    }

    public void syncWalletStatusAfterCommit(UUID orgId, String orgStatus) {
        afterCommitRunner.runAfterCommit(() -> {
            try {
                String key = GatewayRedisKeys.wallet(orgId);
                if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                    redisTemplate.opsForHash().put(key, "status", orgStatus);
                }
            } catch (Exception ex) {
                log.warn("Failed to update Redis wallet status for org {}", orgId, ex);
            }
        });
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize Redis cache value", ex);
        }
    }
}

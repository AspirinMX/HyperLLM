package app.pagefault.hyperllm.admin.config;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.UUID;

/**
 * Synchronizes admin configuration into Redis L2 keys after transaction commits.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCacheSyncService {

    private final StringRedisTemplate redisTemplate;
    private final AfterCommitRunner afterCommitRunner;

    public void evictRoutesAfterCommit(Collection<String> modelCodes) {
        if (modelCodes == null || modelCodes.isEmpty()) {
            return;
        }
        var keys = modelCodes.stream().distinct().map(GatewayRedisKeys::modelRoute).toList();
        afterCommitRunner.runAfterCommit(() -> {
            try {
                redisTemplate.delete(keys);
            } catch (Exception ex) {
                log.warn("Failed to evict Redis route caches {}", keys, ex);
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
                log.warn("Failed to evict {} Redis API key caches", keys.size(), ex);
            }
        });
    }

    public void syncOrgStatusAfterCommit(UUID orgId, String status, Collection<String> orgKeyHashes) {
        evictKeyHashesAfterCommit(orgKeyHashes);
        afterCommitRunner.runAfterCommit(() -> {
            try {
                String walletKey = GatewayRedisKeys.wallet(orgId);
                if (Boolean.TRUE.equals(redisTemplate.hasKey(walletKey))) {
                    redisTemplate.opsForHash().put(walletKey, "status", status);
                }
            } catch (Exception ex) {
                log.warn("Failed to sync Redis wallet status for org {}", orgId, ex);
            }
        });
    }

}

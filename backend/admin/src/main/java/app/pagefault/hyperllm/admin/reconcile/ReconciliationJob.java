package app.pagefault.hyperllm.admin.reconcile;

import app.pagefault.hyperllm.admin.config.AdminProperties;
import app.pagefault.hyperllm.common.billing.LiveWalletReader;
import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.domain.model.billing.Wallet;
import app.pagefault.hyperllm.domain.repository.WalletRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.WALLETS;

/**
 * Periodically compares the ledger, the wallet snapshots, the batches and the live Redis state.
 *
 * <p>Everything an in-flight settlement can explain is attributed to it: while an organisation has
 * unconfirmed settlements, the ledger and Redis are expected to trail the wallet, and the job says
 * so in the finding detail instead of raising a false alarm. A difference with no such evidence is
 * persisted as an open finding for an operator; the job never adjusts money.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationJob {

    private static final Duration PENDING_GRACE = Duration.ofMinutes(15);

    private final DSLContext dsl;
    private final WalletRepository walletRepository;
    private final LiveWalletReader liveWalletReader;
    private final StringRedisTemplate redis;
    private final ReconciliationService findings;
    private final MeterRegistry meterRegistry;
    private final AdminProperties properties;

    @Scheduled(fixedDelayString = "${hyperllm.reconciliation.delay:PT5M}")
    public void reconcile() {
        if (!properties.getReconciliation().isEnabled()) {
            return;
        }
        reconcileOnce(200);
    }

    /** One full pass; returns how many organisations were checked. */
    public int reconcileOnce(int batch) {
        Map<UUID, Pending> pending = pendingSettlements();
        int offset = 0;
        int checked = 0;
        while (true) {
            List<UUID> orgIds = dsl.select(WALLETS.ORG_ID)
                    .from(WALLETS)
                    .orderBy(WALLETS.ORG_ID)
                    .limit(batch)
                    .offset(offset)
                    .fetch(WALLETS.ORG_ID);
            if (orgIds.isEmpty()) {
                return checked;
            }
            for (UUID orgId : orgIds) {
                try {
                    checkOrg(orgId, pending.get(orgId));
                    checked++;
                } catch (RuntimeException ex) {
                    meterRegistry.counter("reconciliation_failures_total").increment();
                    log.warn("Cannot reconcile organisation {}", orgId, ex);
                }
            }
            if (orgIds.size() < batch) {
                return checked;
            }
            offset += batch;
        }
    }

    public record Pending(int count, OffsetDateTime oldest, String oldestEventId) {
    }

    private void checkOrg(UUID orgId, Pending pending) {
        Wallet wallet = walletRepository.findByOrgId(orgId).orElse(null);
        if (wallet == null) {
            return;
        }
        if (pending != null) {
            findings.record(orgId, "PENDING_SETTLEMENT", "WARN", null, null, null,
                    detail(Map.of("count", pending.count(), "oldest", pending.oldest().toString(),
                            "oldestEventId", pending.oldestEventId())));
            return;
        }
        checkLedger(orgId, wallet);
        checkBatches(orgId, wallet);
        checkRedis(orgId, wallet);
        checkEvidence(orgId);
    }

    private void checkLedger(UUID orgId, Wallet wallet) {
        Record row = dsl.fetchOne("""
                SELECT
                    (SELECT coalesce(sum(cash_delta_micros), 0) FROM wallet_ledger WHERE org_id = ?) AS cash_fold,
                    (SELECT coalesce(sum(bonus_delta_micros), 0) FROM wallet_ledger WHERE org_id = ?) AS bonus_fold
                """, orgId, orgId);
        if (row == null) {
            return;
        }
        long cashFold = number(row.get("cash_fold"));
        long bonusFold = number(row.get("bonus_fold"));
        if (cashFold != wallet.cashBalanceMicros() || bonusFold != wallet.bonusBalanceMicros()) {
            findings.record(orgId, "LEDGER_VS_WALLET", "P1",
                    cashFold + bonusFold,
                    wallet.cashBalanceMicros() + wallet.bonusBalanceMicros(),
                    wallet.cashBalanceMicros() + wallet.bonusBalanceMicros() - cashFold - bonusFold,
                    detail(Map.of("cashWallet", wallet.cashBalanceMicros(), "cashFold", cashFold,
                            "bonusWallet", wallet.bonusBalanceMicros(), "bonusFold", bonusFold)));
        }
    }

    private void checkBatches(UUID orgId, Wallet wallet) {
        Long remaining = dsl.fetchOne("""
                SELECT coalesce(sum(remaining_micros), 0) FROM bonus_grants
                WHERE org_id = ? AND status = 'ACTIVE'
                """, orgId).get(0, Long.class);
        long batches = remaining == null ? 0L : remaining;
        if (batches != wallet.bonusBalanceMicros()) {
            findings.record(orgId, "BATCHES_VS_WALLET", "WARN", batches,
                    wallet.bonusBalanceMicros(), wallet.bonusBalanceMicros() - batches,
                    detail(Map.of("activeBatchMicros", batches,
                            "walletBonusMicros", wallet.bonusBalanceMicros())));
        }
    }

    private void checkRedis(UUID orgId, Wallet wallet) {
        LiveWalletReader.LiveWallet live = liveWalletReader.read(orgId);
        if (live == null) {
            return;
        }
        if (live.cashMicros() != wallet.cashBalanceMicros()
                || live.bonusMicros() != wallet.bonusBalanceMicros()) {
            findings.record(orgId, "WALLET_VS_REDIS", "WARN",
                    wallet.cashBalanceMicros() + wallet.bonusBalanceMicros(),
                    live.cashMicros() + live.bonusMicros(),
                    live.cashMicros() + live.bonusMicros()
                            - wallet.cashBalanceMicros() - wallet.bonusBalanceMicros(),
                    detail(Map.of("redisCashMicros", live.cashMicros(),
                            "walletCashMicros", wallet.cashBalanceMicros(),
                            "redisBonusMicros", live.bonusMicros(),
                            "walletBonusMicros", wallet.bonusBalanceMicros())));
        }
    }

    private void checkEvidence(UUID orgId) {
        Long unexplained = dsl.fetchOne("""
                SELECT count(*) FROM wallet_ledger
                WHERE org_id = ? AND created_at >= now() - interval '24 hours'
                  AND (shortfall_micros > 0 OR settled_after_reclaim)
                  AND (hold_id IS NULL OR allocation_detail_json IS NULL)
                """, orgId).get(0, Long.class);
        if (unexplained != null && unexplained > 0) {
            findings.record(orgId, "SHORTFALL_EVIDENCE", "WARN", null, null, null,
                    detail(Map.of("rowsWithoutEvidence", unexplained)));
        }
    }

    private Map<UUID, Pending> pendingSettlements() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(PENDING_GRACE);
        Set<String> due = redis.opsForZSet().rangeByScore(
                GatewayRedisKeys.settlePending(), 0, cutoff.toInstant().toEpochMilli(), 0, 1_000);
        Map<UUID, List<String>> byOrg = new LinkedHashMap<>();
        Map<UUID, OffsetDateTime> oldest = new LinkedHashMap<>();
        if (due == null) {
            return Map.of();
        }
        for (String id : due) {
            Object owner = redis.opsForHash().get(GatewayRedisKeys.settlePendingOwners(), id);
            if (owner == null) {
                continue;
            }
            String[] parts = String.valueOf(owner).split("\\|", 2);
            if (parts.length != 2) {
                continue;
            }
            UUID orgId = UUID.fromString(parts[0]);
            OffsetDateTime at = OffsetDateTime.parse(parts[1]);
            byOrg.computeIfAbsent(orgId, key -> new ArrayList<>()).add(id);
            oldest.merge(orgId, at, (a, b) -> a.isBefore(b) ? a : b);
        }
        Map<UUID, Pending> result = new LinkedHashMap<>();
        byOrg.forEach((orgId, ids) -> result.put(orgId,
                new Pending(ids.size(), oldest.get(orgId), ids.getFirst())));
        return result;
    }

    private static String detail(Map<String, Object> values) {
        return ReconciliationService.detail(values);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}

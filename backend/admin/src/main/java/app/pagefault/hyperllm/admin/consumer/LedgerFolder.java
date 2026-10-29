package app.pagefault.hyperllm.admin.consumer;

import app.pagefault.hyperllm.admin.maintenance.SettlementWorklist;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.messaging.BillingMoneyEvent;
import app.pagefault.hyperllm.domain.model.billing.AuditLogEntry;
import app.pagefault.hyperllm.domain.model.billing.BonusGrant;
import app.pagefault.hyperllm.domain.model.billing.WalletLedgerEntry;
import app.pagefault.hyperllm.domain.model.enums.ActorType;
import app.pagefault.hyperllm.domain.model.enums.EntitlementStatus;
import app.pagefault.hyperllm.domain.model.enums.GrantSource;
import app.pagefault.hyperllm.domain.model.enums.LedgerType;
import app.pagefault.hyperllm.domain.repository.AuditLogRepository;
import app.pagefault.hyperllm.domain.repository.BonusGrantRepository;
import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
import app.pagefault.hyperllm.domain.repository.ResourcePackRepository;
import app.pagefault.hyperllm.domain.repository.WalletLedgerRepository;
import app.pagefault.hyperllm.domain.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.AUDIT_LOGS;
import static app.pagefault.hyperllm.domain.jooq.Tables.OUTBOX_EVENTS;
import static app.pagefault.hyperllm.domain.jooq.Tables.WALLET_LEDGER;

/**
 * Folds one money event into the ledger and the snapshots, atomically.
 *
 * <p>The ledger insert is the gate: it returns false when this event was already folded, and only
 * then are the snapshots touched. That ordering is what keeps the fold identity true - a row exists
 * if and only if its deltas were applied - and it makes a redelivered message a no-op rather than a
 * double charge.
 *
 * <p>Balances are moved by deltas only, never assigned, and never clamped. A balance that would go
 * negative is a defect in the event, so the constraint is allowed to surface it instead of a
 * {@code GREATEST} hiding it.
 */
@Component
@RequiredArgsConstructor
public class LedgerFolder {

    private final WalletLedgerRepository ledgerRepository;
    private final WalletRepository walletRepository;
    private final ResourcePackRepository packRepository;
    private final BonusGrantRepository grantRepository;
    private final OutboxEventRepository outboxRepository;
    private final AuditLogRepository auditLogRepository;
    private final DSLContext dsl;
    private final WalletCreditProjection credits;
    private final SettlementWorklist worklist;

    public enum RepairResult { ADJUSTED, ALREADY_RESOLVED, IN_DELIVERY }

    private static final String WRITE_OFF = "billing.reconcile.write_off";

    /** @return true when this call folded the event, false when it had already been resolved */
    @Transactional
    public boolean fold(BillingMoneyEvent event) {
        long version = lockWallet(event.orgId());
        return !writtenOff(event.eventId(), event.occurredAt())
                && foldLocked(event, ledgerType(event.eventType()), version);
    }

    /** Uses the original event identity and its exact applied deltas, including the batch legs. */
    @Transactional(timeout = 10)
    public RepairResult repair(BillingMoneyEvent event) {
        long version = lockWallet(event.orgId());
        UUID id = event.eventId();
        OffsetDateTime at = event.occurredAt();
        if (resolved(id, at)) {
            return RepairResult.ALREADY_RESOLVED;
        }
        if (enteredDelivery(id)) {
            return RepairResult.IN_DELIVERY;
        }
        if (!foldLocked(event, LedgerType.ADJUST, version)) {
            return RepairResult.ALREADY_RESOLVED;
        }
        audit(event.orgId(), id, at, "billing.reconcile.adjust");
        return RepairResult.ADJUSTED;
    }

    /** Audits the write-off and fences late delivery using the same wallet lock as the fold. */
    @Transactional
    public boolean writeOff(UUID orgId, UUID id, OffsetDateTime at) {
        long version = lockWallet(orgId);
        if (resolved(id, at)) {
            return false;
        }
        if (!walletRepository.applyEventDelta(orgId, 0L, 0L, 0L, 0L, 0L, 0L, id, at)) {
            throw new IllegalStateException("Cannot close settlement " + id);
        }
        worklist.fenceSnapshot(orgId, id, Math.incrementExact(version));
        audit(orgId, id, at, WRITE_OFF);
        return true;
    }

    public boolean enteredDelivery(UUID id) {
        return outboxRepository.exists(id) || outboxRepository.watermark()
                .map(watermark -> id.toString().compareTo(watermark.toString()) <= 0).orElse(false);
    }

    public Optional<String> recordedPayload(UUID id) {
        return dsl.select(OUTBOX_EVENTS.PAYLOAD).from(OUTBOX_EVENTS).where(OUTBOX_EVENTS.ID.eq(id))
                .fetchOptional(record -> record.value1().data());
    }

    public boolean resolved(UUID id, OffsetDateTime at) {
        return dsl.fetchExists(dsl.selectOne().from(WALLET_LEDGER)
                .where(WALLET_LEDGER.ID.eq(id).and(WALLET_LEDGER.CREATED_AT.eq(at)))) || writtenOff(id, at);
    }

    private boolean writtenOff(UUID id, OffsetDateTime at) {
        return dsl.fetchExists(dsl.selectOne().from(AUDIT_LOGS)
                .where(AUDIT_LOGS.ID.eq(id).and(AUDIT_LOGS.CREATED_AT.eq(at)).and(AUDIT_LOGS.ACTION.eq(WRITE_OFF))));
    }

    private long lockWallet(UUID orgId) {
        return walletRepository.findByOrgIdForUpdate(orgId)
                .orElseThrow(() -> new IllegalStateException("Wallet is missing for " + orgId)).version();
    }

    private void audit(UUID orgId, UUID id, OffsetDateTime at, String action) {
        auditLogRepository.insert(new AuditLogEntry(id, ActorType.SYSTEM, null, orgId, action,
                "BILLING_EVENT", id.toString(), null, null, null, at));
    }

    private boolean foldLocked(BillingMoneyEvent event, LedgerType type, long version) {
        OffsetDateTime at = event.occurredAt();
        boolean inserted = ledgerRepository.insertIdempotent(new WalletLedgerEntry(
                event.eventId(),
                event.orgId(),
                event.projectId(),
                event.apiKeyId(),
                type,
                idempotencyKey(event),
                event.cashDeltaMicros(),
                event.bonusDeltaMicros(),
                event.packDeltaUnits(),
                null,
                null,
                event.allocationDetailJson(),
                refRequestId(event),
                null,
                null,
                event.description(),
                at,
                event.holdId(),
                event.shortfallMicros(),
                event.settledAfterReclaim()));
        if (!inserted) {
            return false;
        }

        if (event.creditSource() != null && event.eventType() == BillingMoneyEvent.EventType.BONUS_GRANT) {
            long amount = event.bonusDeltaMicros();
            GrantSource source = event.creditSource() == BillingMoneyEvent.CreditSource.SIGNUP
                    ? GrantSource.SIGNUP : GrantSource.ADMIN_GRANT;
            grantRepository.insert(new BonusGrant(event.eventId(), event.orgId(), source, null, null, null,
                    amount, amount, null, EntitlementStatus.ACTIVE, 0L, at, at));
        }

        long expiredBonusConsumed = 0L;
        for (BillingMoneyEvent.Leg leg : event.legs()) {
            if (leg.virtual() || leg.batchId() == null) {
                // No batch row exists behind this leg; only the wallet snapshot moves.
                continue;
            }
            if (leg.type() == BillingMoneyEvent.LegType.BONUS && leg.micros() > 0L) {
                EntitlementStatus status = grantRepository.foldConsumption(event.orgId(), leg.batchId(), leg.micros(), at)
                        .orElseThrow(() -> batchDeltaFailure(event, leg));
                if (status == EntitlementStatus.EXPIRED) {
                    expiredBonusConsumed = Math.addExact(expiredBonusConsumed, leg.micros());
                }
                continue;
            }
            boolean applied = switch (leg.type()) {
                case PACK -> leg.units() == 0 || packRepository.decrementUnits(leg.batchId(), leg.units(), at);
                case BONUS -> leg.micros() == 0;
                case CASH -> true;
            };
            if (!applied) {
                throw batchDeltaFailure(event, leg);
            }
        }

        // Expiry already debited this part of the batch. Reverse that portion in the ledger in
        // the same transaction, leaving the effective change to the spendable snapshot at zero.
        if (expiredBonusConsumed > 0L) {
            restoreExpiredBonus(event, expiredBonusConsumed);
        }
        Totals totals = Totals.of(event);
        if (!walletRepository.applyEventDelta(
                event.orgId(),
                event.cashDeltaMicros(),
                Math.addExact(event.bonusDeltaMicros(), expiredBonusConsumed),
                totals.recharged(),
                totals.granted(),
                totals.consumedCash(),
                totals.consumedBonus(),
                event.eventId(),
                at)) {
            throw new IllegalStateException("Wallet disappeared during the fold for " + event.eventId());
        }
        if (event.creditSource() != null) {
            credits.apply(event, Math.incrementExact(version));
        } else if (event.eventType() == BillingMoneyEvent.EventType.CONSUME) {
            worklist.fenceSnapshot(event.orgId(), event.eventId(), Math.incrementExact(version));
        }
        return true;
    }

    private static IllegalStateException batchDeltaFailure(BillingMoneyEvent event, BillingMoneyEvent.Leg leg) {
        return new IllegalStateException("Cannot apply batch delta from event " + event.eventId()
                + " to batch " + leg.batchId());
    }

    private void restoreExpiredBonus(BillingMoneyEvent event, long micros) {
        if (!ledgerRepository.insertIdempotent(new WalletLedgerEntry(
                UuidV7.next(), event.orgId(), event.projectId(), event.apiKeyId(), LedgerType.ADJUST,
                "reverse-expiry:" + event.eventId(), 0L, micros, 0L, null, null, null,
                refRequestId(event), null, null, "Reverse expiry for settled bonus consumption",
                event.occurredAt(), event.holdId()))) {
            throw new IllegalStateException("Cannot reverse expiry for " + event.eventId());
        }
    }

    private static LedgerType ledgerType(BillingMoneyEvent.EventType type) {
        return switch (type) {
            case CONSUME -> LedgerType.CONSUME;
            case RECHARGE -> LedgerType.RECHARGE;
            case BONUS_GRANT -> LedgerType.BONUS_GRANT;
            case PACK_GRANT, ADJUST -> LedgerType.ADJUST;
            case BONUS_EXPIRE -> LedgerType.BONUS_EXPIRE;
        };
    }

    private static String idempotencyKey(BillingMoneyEvent event) {
        if (event.idempotencyKey() != null && !event.idempotencyKey().isBlank()) {
            return event.idempotencyKey();
        }
        return "outbox:" + event.eventId();
    }

    private static String refRequestId(BillingMoneyEvent event) {
        String key = event.idempotencyKey();
        return key != null && key.startsWith("req:") ? key.substring(4) : null;
    }

    /** Which cumulative columns this event type moves. */
    private record Totals(long recharged, long granted, long consumedCash, long consumedBonus) {

        static Totals of(BillingMoneyEvent event) {
            return switch (event.eventType()) {
                case CONSUME -> new Totals(0L, 0L,
                        Math.max(0L, -event.cashDeltaMicros()), Math.max(0L, -event.bonusDeltaMicros()));
                case RECHARGE -> new Totals(Math.max(0L, event.cashDeltaMicros()), 0L, 0L, 0L);
                case BONUS_GRANT -> new Totals(0L, Math.max(0L, event.bonusDeltaMicros()), 0L, 0L);
                default -> new Totals(0L, 0L, 0L, 0L);
            };
        }
    }
}

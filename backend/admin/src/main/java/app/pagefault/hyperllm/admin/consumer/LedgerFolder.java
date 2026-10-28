package app.pagefault.hyperllm.admin.consumer;

import app.pagefault.hyperllm.common.messaging.BillingMoneyEvent;
import app.pagefault.hyperllm.domain.model.billing.AuditLogEntry;
import app.pagefault.hyperllm.domain.model.billing.WalletLedgerEntry;
import app.pagefault.hyperllm.domain.model.enums.ActorType;
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
import static app.pagefault.hyperllm.domain.jooq.Tables.WALLETS;
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

    public enum RepairResult { ADJUSTED, ALREADY_RESOLVED, IN_DELIVERY }

    private static final String WRITE_OFF = "billing.reconcile.write_off";

    /** @return true when this call folded the event, false when it had already been resolved */
    @Transactional
    public boolean fold(BillingMoneyEvent event) {
        lockWallet(event.orgId());
        return !writtenOff(event.eventId(), event.occurredAt()) && foldLocked(event, ledgerType(event.eventType()));
    }

    /** Uses the original event identity and its exact applied deltas, including the batch legs. */
    @Transactional(timeout = 10)
    public RepairResult repair(BillingMoneyEvent event) {
        lockWallet(event.orgId());
        UUID id = event.eventId();
        OffsetDateTime at = event.occurredAt();
        if (resolved(id, at)) {
            return RepairResult.ALREADY_RESOLVED;
        }
        if (enteredDelivery(id)) {
            return RepairResult.IN_DELIVERY;
        }
        if (!foldLocked(event, LedgerType.ADJUST)) {
            return RepairResult.ALREADY_RESOLVED;
        }
        audit(event.orgId(), id, at, "billing.reconcile.adjust");
        return RepairResult.ADJUSTED;
    }

    /** Audits the write-off and fences late delivery using the same wallet lock as the fold. */
    @Transactional
    public boolean writeOff(UUID orgId, UUID id, OffsetDateTime at) {
        lockWallet(orgId);
        if (resolved(id, at)) {
            return false;
        }
        dsl.update(WALLETS).set(WALLETS.LAST_APPLIED_EVENT_ID, id)
                .where(WALLETS.ORG_ID.eq(orgId))
                .and(WALLETS.LAST_APPLIED_EVENT_ID.isNull().or(WALLETS.LAST_APPLIED_EVENT_ID.lt(id)))
                .execute();
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

    private void lockWallet(UUID orgId) {
        dsl.select(WALLETS.ORG_ID).from(WALLETS).where(WALLETS.ORG_ID.eq(orgId)).forUpdate().fetchOptional()
                .orElseThrow(() -> new IllegalStateException("Wallet is missing for " + orgId));
    }

    private void audit(UUID orgId, UUID id, OffsetDateTime at, String action) {
        auditLogRepository.insert(new AuditLogEntry(id, ActorType.SYSTEM, null, orgId, action,
                "BILLING_EVENT", id.toString(), null, null, null, at));
    }

    private boolean foldLocked(BillingMoneyEvent event, LedgerType type) {
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

        for (BillingMoneyEvent.Leg leg : event.legs()) {
            if (leg.virtual() || leg.batchId() == null) {
                // No batch row exists behind this leg; only the wallet snapshot moves.
                continue;
            }
            boolean applied = switch (leg.type()) {
                case PACK -> leg.units() == 0 || packRepository.decrementUnits(leg.batchId(), leg.units(), at);
                case BONUS -> leg.micros() == 0 || grantRepository.decrementMicros(leg.batchId(), leg.micros(), at);
                case CASH -> true;
            };
            if (!applied) {
                throw new IllegalStateException("Cannot apply batch delta from event " + event.eventId()
                        + " to batch " + leg.batchId());
            }
        }

        Totals totals = Totals.of(event);
        if (!walletRepository.applyEventDelta(
                event.orgId(),
                event.cashDeltaMicros(),
                event.bonusDeltaMicros(),
                totals.recharged(),
                totals.granted(),
                totals.consumedCash(),
                totals.consumedBonus(),
                event.eventId(),
                at)) {
            throw new IllegalStateException("Wallet disappeared during the fold for " + event.eventId());
        }
        return true;
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

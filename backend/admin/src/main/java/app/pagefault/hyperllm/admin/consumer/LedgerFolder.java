package app.pagefault.hyperllm.admin.consumer;

import app.pagefault.hyperllm.common.messaging.BillingMoneyEvent;
import app.pagefault.hyperllm.domain.model.billing.WalletLedgerEntry;
import app.pagefault.hyperllm.domain.model.enums.LedgerType;
import app.pagefault.hyperllm.domain.repository.BonusGrantRepository;
import app.pagefault.hyperllm.domain.repository.ResourcePackRepository;
import app.pagefault.hyperllm.domain.repository.WalletLedgerRepository;
import app.pagefault.hyperllm.domain.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

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

    /** @return true when this call folded the event, false when it had already been folded */
    @Transactional
    public boolean fold(BillingMoneyEvent event) {
        OffsetDateTime at = event.occurredAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : event.occurredAt();
        boolean inserted = ledgerRepository.insertIdempotent(new WalletLedgerEntry(
                event.eventId(),
                event.orgId(),
                event.projectId(),
                event.apiKeyId(),
                ledgerType(event.eventType()),
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
            if (leg.type() == BillingMoneyEvent.LegType.PACK) {
                packRepository.decrementUnits(leg.batchId(), leg.units(), at);
            } else if (leg.type() == BillingMoneyEvent.LegType.BONUS) {
                grantRepository.decrementMicros(leg.batchId(), leg.micros(), at);
            }
        }

        Totals totals = Totals.of(event);
        walletRepository.applyEventDelta(
                event.orgId(),
                event.cashDeltaMicros(),
                event.bonusDeltaMicros(),
                totals.recharged(),
                totals.granted(),
                totals.consumedCash(),
                totals.consumedBonus(),
                event.eventId(),
                at);
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

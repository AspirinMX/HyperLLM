package app.pagefault.hyperllm.common.messaging;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One change to an organisation's balance, in the shape the ledger folds.
 *
 * <p>The deltas are the <strong>applied</strong> amounts: whatever the store actually moved, never
 * what the caller asked for. A charge that could not be covered reports the uncovered part in
 * {@code shortfallMicros} instead of inflating the delta, so folding the ledger can never disagree
 * with the snapshot it was written beside.
 *
 * <p>Every leg names the batch it touched, and a leg without a row behind it (the historical bonus
 * balance that predates batches) is flagged {@code virtual} so the consumer knows not to look for a
 * batch to decrement.
 */
public record BillingMoneyEvent(
        UUID eventId,
        EventType eventType,
        OffsetDateTime occurredAt,
        UUID orgId,
        UUID projectId,
        UUID apiKeyId,
        String idempotencyKey,
        long cashDeltaMicros,
        long bonusDeltaMicros,
        long packDeltaUnits,
        long shortfallMicros,
        List<Leg> legs,
        /** Self-contained allocation detail; the ledger stores it verbatim. */
        String allocationDetailJson,
        UUID holdId,
        boolean settledAfterReclaim,
        String description
) {

    /** What kind of change this is; maps to the ledger row type on the consumer side. */
    public enum EventType {
        CONSUME,
        RECHARGE,
        BONUS_GRANT,
        PACK_GRANT,
        BONUS_EXPIRE,
        ADJUST
    }

    public enum LegType {
        PACK,
        BONUS,
        CASH
    }

    /**
     * One line of the allocation.
     *
     * @param batchId the resource pack or bonus grant, {@code null} for cash
     * @param units   units of a pack; meaningless for the money denominated legs
     * @param micros  the money value of the line, always meaningful
     * @param virtual true when no batch row exists behind this leg
     */
    public record Leg(LegType type, UUID batchId, long units, long micros, boolean virtual) {

        public static Leg pack(UUID packId, long units, long micros) {
            return new Leg(LegType.PACK, packId, units, micros, false);
        }

        public static Leg bonus(UUID grantId, long micros) {
            return new Leg(LegType.BONUS, grantId, 0L, micros, false);
        }

        public static Leg virtualBonus(long micros) {
            return new Leg(LegType.BONUS, null, 0L, micros, true);
        }

        public static Leg cash(long micros) {
            return new Leg(LegType.CASH, null, 0L, micros, false);
        }
    }

    public BillingMoneyEvent {
        legs = legs == null ? List.of() : List.copyOf(legs);
    }
}

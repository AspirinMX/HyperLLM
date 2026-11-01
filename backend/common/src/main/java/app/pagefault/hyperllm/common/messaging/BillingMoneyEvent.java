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
 *
 * <p>A credit carries its own {@link Grant}: the batch the consumer has to create, the models it
 * covers and the moment it expires. The batch identity is the event id, which is what makes a
 * redelivery insert the same rows instead of a second copy of them.
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
        String description,
        CreditSource creditSource,
        Grant grant,
        UUID orderId
) {

    /** Set by the durable credit producer. Older payloads keep their original fold behavior. */
    public enum CreditSource { ADMIN, SIGNUP, PAYMENT, VOUCHER }

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

    /** The kind of batch a credit creates. Cash has no batch and never reaches this enum. */
    public enum GrantType {
        PACK,
        BONUS
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

    /**
     * The batch a credit creates.
     *
     * @param batchId          identity of the batch, always the event id
     * @param name             pack display name or voucher name, for the operator views
     * @param unitType         {@code TOKENS} or {@code CALLS} for a pack; {@code null} for bonus
     * @param units            pack units, zero for bonus
     * @param micros           bonus value, zero for a pack
     * @param applicableModels {@code null} means every model
     */
    public record Grant(
            GrantType type,
            UUID batchId,
            String name,
            String unitType,
            long units,
            long micros,
            List<String> applicableModels,
            OffsetDateTime expiresAt,
            UUID voucherId,
            UUID redemptionCodeId
    ) {

        public Grant {
            applicableModels = applicableModels == null ? null : List.copyOf(applicableModels);
        }

        public static Grant pack(UUID batchId, String name, String unitType, long units,
                                 List<String> applicableModels, OffsetDateTime expiresAt) {
            return new Grant(GrantType.PACK, batchId, name, unitType, units, 0L,
                    applicableModels, expiresAt, null, null);
        }

        public static Grant bonus(UUID batchId, long micros, List<String> applicableModels,
                                  OffsetDateTime expiresAt, UUID voucherId, UUID redemptionCodeId) {
            return new Grant(GrantType.BONUS, batchId, null, null, 0L, micros,
                    applicableModels, expiresAt, voucherId, redemptionCodeId);
        }

        public Grant withBatchId(UUID id) {
            return new Grant(type, id, name, unitType, units, micros, applicableModels, expiresAt,
                    voucherId, redemptionCodeId);
        }
    }

    /** Payload written before typed grants existed. */
    public BillingMoneyEvent(UUID eventId, EventType eventType, OffsetDateTime occurredAt, UUID orgId,
                             UUID projectId, UUID apiKeyId, String idempotencyKey, long cashDeltaMicros,
                             long bonusDeltaMicros, long packDeltaUnits, long shortfallMicros, List<Leg> legs,
                             String allocationDetailJson, UUID holdId, boolean settledAfterReclaim,
                             String description) {
        this(eventId, eventType, occurredAt, orgId, projectId, apiKeyId, idempotencyKey, cashDeltaMicros,
                bonusDeltaMicros, packDeltaUnits, shortfallMicros, legs, allocationDetailJson, holdId,
                settledAfterReclaim, description, null, null, null);
    }

    /** Payload written by the first durable credit producer, before grants carried their terms. */
    public BillingMoneyEvent(UUID eventId, EventType eventType, OffsetDateTime occurredAt, UUID orgId,
                             UUID projectId, UUID apiKeyId, String idempotencyKey, long cashDeltaMicros,
                             long bonusDeltaMicros, long packDeltaUnits, long shortfallMicros, List<Leg> legs,
                             String allocationDetailJson, UUID holdId, boolean settledAfterReclaim,
                             String description, CreditSource creditSource) {
        this(eventId, eventType, occurredAt, orgId, projectId, apiKeyId, idempotencyKey, cashDeltaMicros,
                bonusDeltaMicros, packDeltaUnits, shortfallMicros, legs, allocationDetailJson, holdId,
                settledAfterReclaim, description, creditSource, null, null);
    }

    public BillingMoneyEvent {
        legs = legs == null ? List.of() : List.copyOf(legs);
    }
}

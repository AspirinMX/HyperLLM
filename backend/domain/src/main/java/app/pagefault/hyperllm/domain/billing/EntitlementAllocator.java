package app.pagefault.hyperllm.domain.billing;

import app.pagefault.hyperllm.common.money.CostMath;
import app.pagefault.hyperllm.domain.model.enums.PackUnitType;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits one charge across resource packs, bonus grants and cash, in that order.
 *
 * <p>Packs are unit-denominated while grants and cash are money-denominated, so the first tier has
 * to convert units into money. That is done proportionally against what is still uncovered: each
 * pack takes {@code min(remainingUnits, remainingTokens)} units and is credited with the matching
 * share of {@code remainingMicros}. Because every step reads the running remainder, rounding cannot
 * accumulate and the plan always satisfies the charged-equals-allocated invariant.
 */
public final class EntitlementAllocator {

    private EntitlementAllocator() {
    }

    /**
     * @param chargedMicros the amount actually owed after pricing and discount
     * @param billableTokens input plus output plus cached plus reasoning tokens, used to value
     *                       token packs; ignored by call packs
     * @param snapshot       entitlement state ordered by expiry ascending
     */
    public static AllocationPlan allocate(long chargedMicros, long billableTokens, EntitlementSnapshot snapshot) {
        if (chargedMicros <= 0L) {
            return new AllocationPlan(List.of(), 0L, 0L, false);
        }
        List<AllocationLeg> legs = new ArrayList<>();
        long remainingMicros = chargedMicros;
        long remainingTokens = Math.max(0L, billableTokens);

        for (EntitlementSnapshot.PackBucket pack : snapshot.packs()) {
            if (remainingMicros <= 0L) {
                break;
            }
            long takeUnits;
            long micros;
            if (pack.unitType() == PackUnitType.CALLS) {
                takeUnits = Math.min(pack.remainingUnits(), 1L);
                micros = remainingMicros;
            } else {
                if (remainingTokens <= 0L) {
                    continue;
                }
                takeUnits = Math.min(pack.remainingUnits(), remainingTokens);
                micros = CostMath.proportionalShareMicros(remainingMicros, takeUnits, remainingTokens);
                remainingTokens -= takeUnits;
            }
            if (takeUnits <= 0L || micros <= 0L) {
                continue;
            }
            legs.add(AllocationLeg.pack(pack.packId(), takeUnits, micros));
            remainingMicros -= micros;
        }

        for (EntitlementSnapshot.BonusBucket grant : snapshot.grants()) {
            if (remainingMicros <= 0L) {
                break;
            }
            long taken = Math.min(grant.remainingMicros(), remainingMicros);
            if (taken <= 0L) {
                continue;
            }
            legs.add(AllocationLeg.bonus(grant.grantId(), taken));
            remainingMicros -= taken;
        }

        long shortfall = 0L;
        if (remainingMicros > 0L) {
            long cashTaken = Math.min(Math.max(snapshot.cashMicros(), 0L), remainingMicros);
            legs.add(AllocationLeg.cash(cashTaken));
            shortfall = remainingMicros - cashTaken;
        }

        return new AllocationPlan(List.copyOf(legs), chargedMicros, shortfall, false);
    }
}

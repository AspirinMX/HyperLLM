package app.pagefault.hyperllm.gateway.billing;

import app.pagefault.hyperllm.domain.billing.AllocationLeg;
import app.pagefault.hyperllm.domain.billing.AllocationType;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the batches a hold froze into the charges of one settled request.
 *
 * <p>Settling never re-reads availability: the hold already owns those batches, so the exact cost is
 * spread over the frozen legs in their original priority order and capped at what each leg froze.
 * Anything the request cost beyond the hold is charged to cash, which the script caps again at the
 * balance actually available.
 */
public final class HeldLegCharger {

    /** The charges to apply, plus the amount the hold could not cover. */
    public record Charges(List<AllocationLeg> legs, long unfrozenMicros) {
    }

    private HeldLegCharger() {
    }

    public static Charges charge(List<AllocationLeg> held, long exactCostMicros) {
        long remaining = Math.max(0L, exactCostMicros);
        List<AllocationLeg> charges = new ArrayList<>();
        for (AllocationLeg leg : held) {
            if (remaining <= 0L) {
                break;
            }
            long take = Math.min(leg.micros(), remaining);
            if (take <= 0L) {
                continue;
            }
            if (leg.type() == AllocationType.PACK) {
                long units = leg.micros() <= 0L || leg.units() <= 0L
                        ? 0L
                        : (long) Math.ceil((double) leg.units() * take / leg.micros());
                units = Math.min(units, leg.units());
                if (units <= 0L) {
                    continue;
                }
                charges.add(AllocationLeg.pack(leg.id(), units, take));
            } else if (leg.type() == AllocationType.BONUS) {
                charges.add(AllocationLeg.bonus(leg.id(), take));
            } else {
                charges.add(AllocationLeg.cash(take));
            }
            remaining -= take;
        }
        if (remaining > 0L) {
            charges.add(AllocationLeg.cash(remaining));
        }
        return new Charges(charges, remaining);
    }
}

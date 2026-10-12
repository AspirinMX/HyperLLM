package app.pagefault.hyperllm.domain.billing;

import java.util.List;

/**
 * Result of a three-tier allocation.
 *
 * <p>Invariant A1: {@code sum(leg.micros) + shortfallMicros == chargedMicros}. {@code degraded}
 * marks a request that fell back to the bonus-plus-cash path after losing a race on reserved
 * entitlement capacity.
 */
public record AllocationPlan(
        List<AllocationLeg> legs,
        long chargedMicros,
        long shortfallMicros,
        boolean degraded
) {

    public long allocatedMicros() {
        return legs.stream().mapToLong(AllocationLeg::micros).sum();
    }

    public AllocationPlan asDegraded() {
        return new AllocationPlan(legs, chargedMicros, shortfallMicros, true);
    }
}

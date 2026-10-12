package app.pagefault.hyperllm.domain.billing;

import java.util.UUID;

/**
 * One line of an allocation. {@code units} only carries meaning for {@link AllocationType#PACK};
 * {@code micros} is the money value of the line and is what the ledger sums.
 */
public record AllocationLeg(
        AllocationType type,
        UUID id,
        long units,
        long micros
) {

    public static AllocationLeg pack(UUID packId, long units, long micros) {
        return new AllocationLeg(AllocationType.PACK, packId, units, micros);
    }

    public static AllocationLeg bonus(UUID grantId, long micros) {
        return new AllocationLeg(AllocationType.BONUS, grantId, 0L, micros);
    }

    public static AllocationLeg cash(long micros) {
        return new AllocationLeg(AllocationType.CASH, null, 0L, micros);
    }
}

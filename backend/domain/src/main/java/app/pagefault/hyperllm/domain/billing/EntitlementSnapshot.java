package app.pagefault.hyperllm.domain.billing;

import app.pagefault.hyperllm.domain.model.enums.PackUnitType;

import java.util.List;
import java.util.UUID;

/**
 * The entitlement state observed just before allocation. Packs and grants are expected to be
 * already filtered by model/expiry and ordered by {@code expiresAt} ascending, because that order
 * is the deduction order and the allocator must not re-sort (it does not know expiry semantics of
 * nulls for grants).
 */
public record EntitlementSnapshot(
        List<PackBucket> packs,
        List<BonusBucket> grants,
        long cashMicros
) {

    public record PackBucket(UUID packId, PackUnitType unitType, long remainingUnits) {
    }

    public record BonusBucket(UUID grantId, long remainingMicros) {
    }

    public static EntitlementSnapshot empty(long cashMicros) {
        return new EntitlementSnapshot(List.of(), List.of(), cashMicros);
    }

    /** Drops every batch but keeps cash, matching the documented conflict degradation path. */
    public EntitlementSnapshot withoutEntitlements() {
        return new EntitlementSnapshot(List.of(), List.of(), cashMicros);
    }
}

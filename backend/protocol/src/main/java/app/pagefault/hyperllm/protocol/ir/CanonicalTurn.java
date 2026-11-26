package app.pagefault.hyperllm.protocol.ir;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Ordered conversation turn containing one or more typed content parts.
 */
public final class CanonicalTurn {

    private final CanonicalRole role;
    private final List<CanonicalContentPart> parts;

    public CanonicalTurn(CanonicalRole role, List<CanonicalContentPart> parts) {
        this.role = role;
        this.parts = parts == null ? List.of() : List.copyOf(parts);
    }

    private CanonicalTurn(CanonicalRole role, List<CanonicalContentPart> ownedUnmodifiable, boolean owned) {
        this.role = role;
        this.parts = ownedUnmodifiable;
    }

    /**
     * Wraps a freshly constructed, caller-owned list without a redundant {@link List#copyOf} copy.
     */
    public static CanonicalTurn ofOwned(CanonicalRole role, List<CanonicalContentPart> ownedParts) {
        if (ownedParts == null || ownedParts.isEmpty()) {
            return new CanonicalTurn(role, List.of(), true);
        }
        if (ownedParts.size() == 1) {
            return new CanonicalTurn(role, List.of(ownedParts.getFirst()), true);
        }
        return new CanonicalTurn(role, Collections.unmodifiableList(ownedParts), true);
    }

    public CanonicalRole role() {
        return role;
    }

    public List<CanonicalContentPart> parts() {
        return parts;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof CanonicalTurn other) {
            return role == other.role && Objects.equals(parts, other.parts);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, parts);
    }

    @Override
    public String toString() {
        return "CanonicalTurn[role=" + role + ", parts=" + parts + "]";
    }
}

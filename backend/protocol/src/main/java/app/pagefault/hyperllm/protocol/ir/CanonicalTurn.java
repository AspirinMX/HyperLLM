package app.pagefault.hyperllm.protocol.ir;

import java.util.List;

/**
 * Ordered conversation turn containing one or more typed content parts.
 */
public record CanonicalTurn(
        CanonicalRole role,
        List<CanonicalContentPart> parts
) {
    public CanonicalTurn {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }
}

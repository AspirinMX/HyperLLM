package app.pagefault.hyperllm.protocol.ir;

import java.util.List;
import java.util.Map;

/**
 * Inbound transport and protocol-specific options needed for response formatting and request fidelity.
 *
 * <p>{@code vendorRawFields} / {@code vendorRawTools} retain vendor-only members verbatim (raw JSON) so a
 * same-protocol re-encode is lossless. They are populated by the inbound codec and must be cleared for
 * cross-protocol translation, where those members are rejected or dropped by policy.
 */
public record CanonicalProtocolOptions(
        boolean clientRequestedUsage,
        boolean geminiAltSse,
        String anthropicVersion,
        String anthropicBeta,
        String user,
        String serviceTier,
        Boolean store,
        Map<String, String> vendorRawFields,
        List<String> vendorRawTools
) {
    public CanonicalProtocolOptions {
        vendorRawFields = vendorRawFields == null ? Map.of() : Map.copyOf(vendorRawFields);
        vendorRawTools = vendorRawTools == null ? List.of() : List.copyOf(vendorRawTools);
    }

    /** Protocol-specific constructor for codecs that carry no vendor-only members. */
    public CanonicalProtocolOptions(
            boolean clientRequestedUsage,
            boolean geminiAltSse,
            String anthropicVersion,
            String anthropicBeta,
            String user,
            String serviceTier,
            Boolean store
    ) {
        this(clientRequestedUsage, geminiAltSse, anthropicVersion, anthropicBeta, user, serviceTier, store,
                Map.of(), List.of());
    }

    public static CanonicalProtocolOptions defaults() {
        return new CanonicalProtocolOptions(false, true, null, null, null, null, null);
    }

    public boolean hasVendorRaw() {
        return !vendorRawFields.isEmpty() || !vendorRawTools.isEmpty();
    }

    /** Copy with the retained vendor members stripped (cross-protocol translation). */
    public CanonicalProtocolOptions withoutVendorRaw() {
        if (!hasVendorRaw()) {
            return this;
        }
        return new CanonicalProtocolOptions(clientRequestedUsage, geminiAltSse, anthropicVersion, anthropicBeta,
                user, serviceTier, store);
    }
}

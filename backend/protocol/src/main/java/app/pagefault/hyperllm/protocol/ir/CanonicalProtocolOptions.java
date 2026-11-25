package app.pagefault.hyperllm.protocol.ir;

/**
 * Inbound transport and protocol-specific options needed for response formatting and request fidelity.
 */
public record CanonicalProtocolOptions(
        boolean clientRequestedUsage,
        boolean geminiAltSse,
        String anthropicVersion,
        String anthropicBeta,
        String user,
        String serviceTier,
        Boolean store
) {
    public static CanonicalProtocolOptions defaults() {
        return new CanonicalProtocolOptions(false, true, null, null, null, null, null);
    }
}

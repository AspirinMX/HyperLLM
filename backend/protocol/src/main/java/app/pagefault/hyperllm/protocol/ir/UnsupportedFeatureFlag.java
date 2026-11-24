package app.pagefault.hyperllm.protocol.ir;

/**
 * Records a vendor-locked request feature discovered during decoding. Allowed on homomorphic
 * passthrough, rejected fast with HTTP 400 when translating across different protocols.
 */
public record UnsupportedFeatureFlag(
        String featureName,
        String detail
) {
}

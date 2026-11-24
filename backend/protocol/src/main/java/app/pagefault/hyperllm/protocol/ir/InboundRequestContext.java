package app.pagefault.hyperllm.protocol.ir;

/**
 * Inbound transport metadata supplied alongside the raw request JSON body (e.g., model and stream
 * flags extracted from Gemini URL paths, or Anthropic version/beta headers).
 */
public record InboundRequestContext(
        String pathModel,
        Boolean pathStream,
        boolean geminiAltSse,
        String anthropicVersion,
        String anthropicBeta
) {
    public static InboundRequestContext empty() {
        return new InboundRequestContext(null, null, true, null, null);
    }

    public static InboundRequestContext forGemini(String model, boolean stream, boolean altSse) {
        return new InboundRequestContext(model, stream, altSse, null, null);
    }

    public static InboundRequestContext forAnthropic(String anthropicVersion, String anthropicBeta) {
        return new InboundRequestContext(null, null, true, anthropicVersion, anthropicBeta);
    }
}

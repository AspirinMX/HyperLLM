package app.pagefault.hyperllm.protocol.ir;

import java.util.List;

/**
 * Protocol-agnostic Canonical Request IR covering text/image inputs, tool calling,
 * structured outputs, reasoning/thinking controls, and prompt caching.
 */
public record CanonicalRequest(
        ProtocolKind inboundProtocol,
        String model,
        boolean stream,
        List<CanonicalSystemPart> systemInstructions,
        List<CanonicalTurn> turns,
        List<CanonicalToolDefinition> tools,
        CanonicalToolChoice toolChoice,
        CanonicalGenerationConfig generation,
        CanonicalResponseFormat responseFormat,
        CanonicalThinkingConfig thinking,
        CanonicalCacheConfig cache,
        CanonicalProtocolOptions options,
        List<UnsupportedFeatureFlag> vendorOnlyFeatures
) {
    public CanonicalRequest {
        systemInstructions = systemInstructions == null ? List.of() : List.copyOf(systemInstructions);
        turns = turns == null ? List.of() : List.copyOf(turns);
        tools = tools == null ? List.of() : List.copyOf(tools);
        toolChoice = toolChoice == null ? CanonicalToolChoice.omitted() : toolChoice;
        generation = generation == null ? CanonicalGenerationConfig.empty() : generation;
        thinking = thinking == null ? CanonicalThinkingConfig.omitted() : thinking;
        cache = cache == null ? CanonicalCacheConfig.empty() : cache;
        options = options == null ? CanonicalProtocolOptions.defaults() : options;
        vendorOnlyFeatures = vendorOnlyFeatures == null ? List.of() : List.copyOf(vendorOnlyFeatures);
    }

    public Integer maxOutputTokens() {
        return generation.maxOutputTokens();
    }

    public boolean clientRequestedUsage() {
        return options.clientRequestedUsage();
    }

    public boolean hasVendorOnlyFeatures() {
        return !vendorOnlyFeatures.isEmpty();
    }
}

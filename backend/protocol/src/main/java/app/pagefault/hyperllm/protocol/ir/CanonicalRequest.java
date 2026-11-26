package app.pagefault.hyperllm.protocol.ir;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Protocol-agnostic Canonical Request IR covering text/image inputs, tool calling,
 * structured outputs, reasoning/thinking controls, and prompt caching.
 */
public final class CanonicalRequest {

    private final ProtocolKind inboundProtocol;
    private final String model;
    private final boolean stream;
    private final List<CanonicalSystemPart> systemInstructions;
    private final List<CanonicalTurn> turns;
    private final List<CanonicalToolDefinition> tools;
    private final CanonicalToolChoice toolChoice;
    private final CanonicalGenerationConfig generation;
    private final CanonicalResponseFormat responseFormat;
    private final CanonicalThinkingConfig thinking;
    private final CanonicalCacheConfig cache;
    private final CanonicalProtocolOptions options;
    private final List<UnsupportedFeatureFlag> vendorOnlyFeatures;
    private final RequestMetrics metrics;

    public CanonicalRequest(
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
        this(
                inboundProtocol, model, stream,
                systemInstructions == null ? List.of() : List.copyOf(systemInstructions),
                turns == null ? List.of() : List.copyOf(turns),
                tools == null ? List.of() : List.copyOf(tools),
                toolChoice, generation, responseFormat, thinking, cache, options,
                vendorOnlyFeatures == null ? List.of() : List.copyOf(vendorOnlyFeatures),
                RequestMetrics.UNCOMPUTED,
                true
        );
    }

    private CanonicalRequest(
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
            List<UnsupportedFeatureFlag> vendorOnlyFeatures,
            RequestMetrics metrics,
            boolean trustedImmutable
    ) {
        this.inboundProtocol = inboundProtocol;
        this.model = model;
        this.stream = stream;
        this.systemInstructions = systemInstructions;
        this.turns = turns;
        this.tools = tools;
        this.toolChoice = toolChoice == null ? CanonicalToolChoice.omitted() : toolChoice;
        this.generation = generation == null ? CanonicalGenerationConfig.empty() : generation;
        this.responseFormat = responseFormat;
        this.thinking = thinking == null ? CanonicalThinkingConfig.omitted() : thinking;
        this.cache = cache == null ? CanonicalCacheConfig.empty() : cache;
        this.options = options == null ? CanonicalProtocolOptions.defaults() : options;
        this.vendorOnlyFeatures = vendorOnlyFeatures;
        this.metrics = metrics == null ? RequestMetrics.UNCOMPUTED : metrics;
    }

    /**
     * Wraps caller-owned freshly built lists without redundant {@link List#copyOf} array allocations,
     * attaching single-pass fused {@link RequestMetrics}.
     */
    public static CanonicalRequest ofOwned(
            ProtocolKind inboundProtocol,
            String model,
            boolean stream,
            List<CanonicalSystemPart> ownedSystemInstructions,
            List<CanonicalTurn> ownedTurns,
            List<CanonicalToolDefinition> ownedTools,
            CanonicalToolChoice toolChoice,
            CanonicalGenerationConfig generation,
            CanonicalResponseFormat responseFormat,
            CanonicalThinkingConfig thinking,
            CanonicalCacheConfig cache,
            CanonicalProtocolOptions options,
            List<UnsupportedFeatureFlag> ownedVendorOnlyFeatures,
            RequestMetrics metrics
    ) {
        return new CanonicalRequest(
                inboundProtocol,
                model,
                stream,
                freezeOwned(ownedSystemInstructions),
                freezeOwned(ownedTurns),
                freezeOwned(ownedTools),
                toolChoice,
                generation,
                responseFormat,
                thinking,
                cache,
                options,
                freezeOwned(ownedVendorOnlyFeatures),
                metrics,
                true
        );
    }

    private static <T> List<T> freezeOwned(List<T> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(list);
    }

    public ProtocolKind inboundProtocol() {
        return inboundProtocol;
    }

    public String model() {
        return model;
    }

    public boolean stream() {
        return stream;
    }

    public List<CanonicalSystemPart> systemInstructions() {
        return systemInstructions;
    }

    public List<CanonicalTurn> turns() {
        return turns;
    }

    public List<CanonicalToolDefinition> tools() {
        return tools;
    }

    public CanonicalToolChoice toolChoice() {
        return toolChoice;
    }

    public CanonicalGenerationConfig generation() {
        return generation;
    }

    public CanonicalResponseFormat responseFormat() {
        return responseFormat;
    }

    public CanonicalThinkingConfig thinking() {
        return thinking;
    }

    public CanonicalCacheConfig cache() {
        return cache;
    }

    public CanonicalProtocolOptions options() {
        return options;
    }

    public List<UnsupportedFeatureFlag> vendorOnlyFeatures() {
        return vendorOnlyFeatures;
    }

    public RequestMetrics metrics() {
        return metrics;
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

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof CanonicalRequest other) {
            return stream == other.stream
                    && inboundProtocol == other.inboundProtocol
                    && Objects.equals(model, other.model)
                    && Objects.equals(systemInstructions, other.systemInstructions)
                    && Objects.equals(turns, other.turns)
                    && Objects.equals(tools, other.tools)
                    && Objects.equals(toolChoice, other.toolChoice)
                    && Objects.equals(generation, other.generation)
                    && Objects.equals(responseFormat, other.responseFormat)
                    && Objects.equals(thinking, other.thinking)
                    && Objects.equals(cache, other.cache)
                    && Objects.equals(options, other.options)
                    && Objects.equals(vendorOnlyFeatures, other.vendorOnlyFeatures);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                inboundProtocol, model, stream, systemInstructions, turns, tools, toolChoice,
                generation, responseFormat, thinking, cache, options, vendorOnlyFeatures
        );
    }
}

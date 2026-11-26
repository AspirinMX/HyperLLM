package app.pagefault.hyperllm.protocol.ir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;

/**
 * Capability profile for a target upstream wire protocol + provider + model configuration,
 * modeled after OpenCode's capability-driven {@code ProviderTransform}.
 * <p>
 * Never hardcodes model name lists: all capabilities derive strictly from the wire protocol
 * contract, provider identity ({@code providers.code}), {@code virtual_models.supports_thinking},
 * and optional explicit overrides in {@code virtual_models.metadata_json}.
 */
public record ProviderCapabilities(
        ProtocolKind outboundProtocol,
        boolean openAiNativeProvider,
        boolean supportsThinking,
        boolean supportsDeveloperRole,
        boolean useMaxCompletionTokens,
        boolean supportsReasoningEffort,
        boolean forbidsSamplingWithReasoning,
        String interleavedReasoningField,
        int toolCallIdMaxLength,
        boolean toolCallIdAlnumDashUnderscoreOnly,
        boolean allowsEmptyTextBlocks,
        boolean supportsStrictToolSchema,
        boolean supportsToolResultImages
) {

    private static final ObjectMapper METADATA_MAPPER = new ObjectMapper();

    public static ProviderCapabilities resolve(
            ProtocolKind outboundProtocol,
            String providerCode,
            boolean supportsThinking,
            String thinkingStyle,
            String metadataJson
    ) {
        return resolveWithParsedMetadata(
                outboundProtocol, providerCode, supportsThinking, thinkingStyle, parseMetadata(metadataJson));
    }

    /**
     * Resolves capabilities using a pre-parsed {@code metadata_json} {@link JsonNode} so callers
     * caching route metadata can avoid re-parsing the same JSON once per channel/protocol pair.
     */
    public static ProviderCapabilities resolveWithParsedMetadata(
            ProtocolKind outboundProtocol,
            String providerCode,
            boolean supportsThinking,
            String thinkingStyle,
            JsonNode preParsedMetadata
    ) {
        ProtocolKind protocol = outboundProtocol == null ? ProtocolKind.OPENAI_CHAT : outboundProtocol;
        String provider = providerCode == null ? "" : providerCode.trim().toUpperCase(Locale.ROOT);
        String style = thinkingStyle == null ? "" : thinkingStyle.trim().toUpperCase(Locale.ROOT);
        JsonNode meta = (preParsedMetadata != null && preParsedMetadata.isObject()) ? preParsedMetadata : null;

        boolean effectiveThinking = supportsThinking || !style.isEmpty()
                || boolOverride(meta, "supportsThinking", false);

        ProviderCapabilities base = switch (protocol) {
            case OPENAI_CHAT -> resolveOpenAiChat(provider, effectiveThinking, style);
            case OPENAI_RESPONSES -> resolveOpenAiResponses(effectiveThinking);
            case ANTHROPIC_MESSAGES -> new ProviderCapabilities(
                    ProtocolKind.ANTHROPIC_MESSAGES,
                    false,
                    effectiveThinking,
                    false,
                    false,
                    false,
                    true,
                    null,
                    64,
                    true,
                    false,
                    true,
                    true
            );
            case GEMINI_GENERATE_CONTENT -> new ProviderCapabilities(
                    ProtocolKind.GEMINI_GENERATE_CONTENT,
                    false,
                    effectiveThinking,
                    false,
                    false,
                    false,
                    false,
                    null,
                    64,
                    false,
                    false,
                    false,
                    true
            );
        };

        return applyMetadataOverrides(base, meta);
    }

    private static ProviderCapabilities resolveOpenAiChat(String provider, boolean supportsThinking, String style) {
        boolean openAiNative = "OPENAI".equals(provider) || "AZURE_OPENAI".equals(provider);
        boolean supportsEffort = (openAiNative && supportsThinking) || "OPENAI_EFFORT".equals(style);
        boolean forbidsSampling = openAiNative && supportsThinking;

        String interleavedField;
        if ("INTERLEAVED_REASONING_CONTENT".equals(style)) {
            interleavedField = "reasoning_content";
        } else if ("INTERLEAVED_REASONING".equals(style)) {
            interleavedField = "reasoning";
        } else if (!openAiNative && supportsThinking) {
            // Standard de facto field across OpenAI-compatible reasoning servers
            // (DeepSeek, Qwen, Kimi, GLM, MiniMax, Doubao, vLLM, SGLang, SiliconFlow, etc.)
            interleavedField = "reasoning_content";
        } else {
            interleavedField = null;
        }

        return new ProviderCapabilities(
                ProtocolKind.OPENAI_CHAT,
                openAiNative,
                supportsThinking,
                openAiNative,
                openAiNative,
                supportsEffort,
                forbidsSampling,
                interleavedField,
                40,
                false,
                true,
                openAiNative,
                false
        );
    }

    private static ProviderCapabilities resolveOpenAiResponses(boolean supportsThinking) {
        return new ProviderCapabilities(
                ProtocolKind.OPENAI_RESPONSES,
                true,
                supportsThinking,
                true,
                false,
                true,
                supportsThinking,
                null,
                64,
                false,
                true,
                true,
                true
        );
    }

    private static ProviderCapabilities applyMetadataOverrides(ProviderCapabilities base, JsonNode meta) {
        if (meta == null || !meta.isObject() || meta.isEmpty()) {
            return base;
        }
        String interleaved = base.interleavedReasoningField();
        if (meta.has("interleavedReasoningField")) {
            JsonNode fieldNode = meta.get("interleavedReasoningField");
            if (fieldNode.isNull() || (fieldNode.isBoolean() && !fieldNode.asBoolean())) {
                interleaved = null;
            } else if (fieldNode.isTextual()) {
                String raw = fieldNode.asText().trim();
                interleaved = (raw.isEmpty() || "none".equalsIgnoreCase(raw)) ? null : raw;
            }
        }
        int toolCallIdMaxLen = base.toolCallIdMaxLength();
        if (meta.hasNonNull("toolCallIdMaxLength") && meta.get("toolCallIdMaxLength").isIntegralNumber()) {
            int configured = meta.get("toolCallIdMaxLength").asInt();
            if (configured >= 16) {
                toolCallIdMaxLen = configured;
            }
        }
        return new ProviderCapabilities(
                base.outboundProtocol(),
                base.openAiNativeProvider(),
                base.supportsThinking(),
                boolOverride(meta, "supportsDeveloperRole", base.supportsDeveloperRole()),
                boolOverride(meta, "useMaxCompletionTokens", base.useMaxCompletionTokens()),
                boolOverride(meta, "supportsReasoningEffort", base.supportsReasoningEffort()),
                boolOverride(meta, "forbidsSamplingWithReasoning", base.forbidsSamplingWithReasoning()),
                interleaved,
                toolCallIdMaxLen,
                boolOverride(meta, "toolCallIdAlnumDashUnderscoreOnly", base.toolCallIdAlnumDashUnderscoreOnly()),
                boolOverride(meta, "allowsEmptyTextBlocks", base.allowsEmptyTextBlocks()),
                boolOverride(meta, "supportsStrictToolSchema", base.supportsStrictToolSchema()),
                boolOverride(meta, "supportsToolResultImages", base.supportsToolResultImages())
        );
    }

    private static JsonNode parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank() || "{}".equals(metadataJson.trim())) {
            return null;
        }
        try {
            JsonNode tree = METADATA_MAPPER.readTree(metadataJson);
            return tree != null && tree.isObject() ? tree : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean boolOverride(JsonNode meta, String field, boolean fallback) {
        if (meta != null && meta.hasNonNull(field) && meta.get(field).isBoolean()) {
            return meta.get(field).asBoolean();
        }
        return fallback;
    }
}

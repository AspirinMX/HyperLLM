package app.pagefault.hyperllm.protocol.adapter;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.protocol.ir.CanonicalChatRequest;
import app.pagefault.hyperllm.protocol.ir.CanonicalMessage;
import app.pagefault.hyperllm.protocol.ir.PreparedUpstreamRequest;
import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.ir.TokenUsage;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Anthropic {@code /v1/messages} protocol adapter supporting request validation, native passthrough,
 * {@code message_start}/{@code content_block_delta}/{@code message_delta} SSE usage sniffing,
 * and length-based token estimation on premature termination.
 */
public final class AnthropicMessagesProtocolAdapter implements ProtocolAdapter {

    private final ObjectMapper objectMapper;
    private final TokenEstimator tokenEstimator;

    public AnthropicMessagesProtocolAdapter(ObjectMapper objectMapper, TokenEstimator tokenEstimator) {
        this.objectMapper = objectMapper;
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public ProtocolKind kind() {
        return ProtocolKind.ANTHROPIC_MESSAGES;
    }

    @Override
    public CanonicalChatRequest parseRequest(byte[] rawBody) {
        return toCanonical(ProtocolSupport.parseObjectRoot(objectMapper, rawBody));
    }

    @Override
    public PreparedUpstreamRequest prepareUpstreamRequest(byte[] rawBody, String upstreamModelName) {
        ObjectNode root = ProtocolSupport.parseObjectRoot(objectMapper, rawBody);
        CanonicalChatRequest canonical = toCanonical(root);
        ProtocolSupport.rewriteModelIfPresent(root, upstreamModelName);
        return new PreparedUpstreamRequest(canonical, ProtocolSupport.writeJsonBytes(objectMapper, root));
    }

    @Override
    public Optional<TokenUsage> extractNonStreamingUsage(byte[] responseBody) {
        if (responseBody == null || responseBody.length == 0) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode usage = root.get("usage");
            if (usage == null || !usage.isObject()) {
                return Optional.empty();
            }
            return Optional.of(parseUsageObject(usage));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    @Override
    public StreamUsageTracker newStreamTracker(CanonicalChatRequest request) {
        return new AnthropicStreamTracker(request);
    }

    private CanonicalChatRequest toCanonical(ObjectNode root) {
        String model = ProtocolSupport.requireModel(root);
        JsonNode maxTokensNode = root.get("max_tokens");
        if (maxTokensNode == null || !maxTokensNode.isIntegralNumber() || maxTokensNode.asInt() <= 0) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Field 'max_tokens' must be a positive integer");
        }
        JsonNode messagesNode = root.get("messages");
        if (messagesNode == null || !messagesNode.isArray() || messagesNode.isEmpty()) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Field 'messages' must be a non-empty array");
        }
        boolean stream = root.path("stream").asBoolean(false);

        List<CanonicalMessage> messages = new ArrayList<>(messagesNode.size() + 1);
        StringBuilder rawPrompt = new StringBuilder();

        JsonNode systemNode = root.get("system");
        if (systemNode != null && !systemNode.isNull()) {
            String systemText = ProtocolSupport.extractTextContent(systemNode);
            if (!systemText.isEmpty()) {
                messages.add(new CanonicalMessage("system", systemText));
                rawPrompt.append(systemText);
            }
        }
        ProtocolSupport.appendMessages(messagesNode, messages, rawPrompt);

        return new CanonicalChatRequest(
                ProtocolKind.ANTHROPIC_MESSAGES,
                model,
                stream,
                maxTokensNode.asInt(),
                false,
                List.copyOf(messages),
                rawPrompt.toString()
        );
    }

    private static TokenUsage parseUsageObject(JsonNode usage) {
        int input = Math.max(0, usage.path("input_tokens").asInt(0));
        int output = Math.max(0, usage.path("output_tokens").asInt(0));
        int cacheRead = Math.max(0, usage.path("cache_read_input_tokens").asInt(0));
        int cacheWrite = Math.max(0, usage.path("cache_creation_input_tokens").asInt(0));
        return new TokenUsage(input, cacheRead, cacheWrite, output, 0, false);
    }

    private final class AnthropicStreamTracker implements StreamUsageTracker {

        private final CanonicalChatRequest request;
        private final SseEventSplitter splitter = new SseEventSplitter();
        private final StringBuilder streamedText = new StringBuilder();
        private TokenUsage observed = TokenUsage.empty();
        private boolean sawInputUsage;
        private boolean sawOutputUsage;

        private AnthropicStreamTracker(CanonicalChatRequest request) {
            this.request = request;
        }

        @Override
        public List<byte[]> feed(byte[] chunk) {
            List<SseEventSplitter.SseEvent> events = splitter.push(chunk);
            return inspectEvents(events);
        }

        @Override
        public List<byte[]> complete() {
            return inspectEvents(splitter.flushRemainder());
        }

        @Override
        public TokenUsage finalizeUsage(boolean cleanCompletion) {
            if (cleanCompletion && sawInputUsage && sawOutputUsage) {
                return observed;
            }
            if (sawInputUsage && sawOutputUsage && observed.completionTokens() > 0) {
                return observed;
            }
            int prompt = sawInputUsage
                    ? observed.promptTokens()
                    : tokenEstimator.estimatePromptTokens(request);
            int completion = (cleanCompletion && sawOutputUsage)
                    ? observed.completionTokens()
                    : tokenEstimator.estimateCompletionTokens(request.model(), streamedText);
            return new TokenUsage(
                    prompt,
                    observed.cachedReadTokens(),
                    observed.cachedWriteTokens(),
                    completion,
                    0,
                    !(sawInputUsage && sawOutputUsage && cleanCompletion)
            );
        }

        private List<byte[]> inspectEvents(List<SseEventSplitter.SseEvent> events) {
            if (events.isEmpty()) {
                return List.of();
            }
            List<byte[]> out = new ArrayList<>(events.size());
            for (SseEventSplitter.SseEvent event : events) {
                inspectPayload(event);
                out.add(event.rawBytes());
            }
            return out;
        }

        private void inspectPayload(SseEventSplitter.SseEvent event) {
            String data = event.dataPayload();
            if (data.isEmpty()) {
                return;
            }
            try {
                JsonNode root = objectMapper.readTree(data);
                if (!root.isObject()) {
                    return;
                }
                String type = root.path("type").asText(event.eventName());
                switch (type) {
                    case "message_start" -> {
                        JsonNode usage = root.path("message").path("usage");
                        if (usage.isObject()) {
                            observed = observed.mergeMax(parseUsageObject(usage));
                            sawInputUsage = true;
                        }
                    }
                    case "content_block_delta" -> {
                        JsonNode delta = root.get("delta");
                        if (delta != null && delta.isObject()) {
                            if (delta.hasNonNull("text") && delta.get("text").isTextual()) {
                                ProtocolSupport.appendBounded(streamedText, delta.get("text").asText());
                            }
                            if (delta.hasNonNull("thinking") && delta.get("thinking").isTextual()) {
                                ProtocolSupport.appendBounded(streamedText, delta.get("thinking").asText());
                            }
                            if (delta.hasNonNull("partial_json") && delta.get("partial_json").isTextual()) {
                                ProtocolSupport.appendBounded(streamedText, delta.get("partial_json").asText());
                            }
                        }
                    }
                    case "message_delta" -> {
                        JsonNode usage = root.get("usage");
                        if (usage != null && usage.isObject()) {
                            observed = observed.mergeMax(parseUsageObject(usage));
                            if (usage.hasNonNull("output_tokens")) {
                                sawOutputUsage = true;
                            }
                        }
                    }
                    default -> {
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }
}

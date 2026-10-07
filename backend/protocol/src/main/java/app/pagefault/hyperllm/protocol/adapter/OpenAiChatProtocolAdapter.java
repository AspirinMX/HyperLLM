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
 * OpenAI {@code /v1/chat/completions} protocol adapter with streaming {@code include_usage} injection,
 * trailing chunk stripping when the caller did not request usage, and jtokkit fallback estimation.
 */
public final class OpenAiChatProtocolAdapter implements ProtocolAdapter {

    private final ObjectMapper objectMapper;
    private final TokenEstimator tokenEstimator;

    public OpenAiChatProtocolAdapter(ObjectMapper objectMapper, TokenEstimator tokenEstimator) {
        this.objectMapper = objectMapper;
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public ProtocolKind kind() {
        return ProtocolKind.OPENAI_CHAT;
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

        if (canonical.stream() && !canonical.clientRequestedUsage()) {
            JsonNode streamOptions = root.get("stream_options");
            ObjectNode optionsObj = (streamOptions instanceof ObjectNode existing)
                    ? existing
                    : objectMapper.createObjectNode();
            optionsObj.put("include_usage", true);
            root.set("stream_options", optionsObj);
        }

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
            return Optional.of(parseUsageNode(usage));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    @Override
    public StreamUsageTracker newStreamTracker(CanonicalChatRequest request) {
        return new OpenAiStreamTracker(request);
    }

    private CanonicalChatRequest toCanonical(ObjectNode root) {
        String model = ProtocolSupport.requireModel(root);
        JsonNode messagesNode = root.get("messages");
        if (messagesNode == null || !messagesNode.isArray() || messagesNode.isEmpty()) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Field 'messages' must be a non-empty array");
        }
        boolean stream = root.path("stream").asBoolean(false);
        Integer maxTokens = extractMaxTokens(root);
        boolean clientRequestedUsage = root.path("stream_options").path("include_usage").asBoolean(false);

        List<CanonicalMessage> messages = new ArrayList<>(messagesNode.size());
        StringBuilder rawPrompt = new StringBuilder();
        ProtocolSupport.appendMessages(messagesNode, messages, rawPrompt);

        return new CanonicalChatRequest(
                ProtocolKind.OPENAI_CHAT,
                model,
                stream,
                maxTokens,
                clientRequestedUsage,
                List.copyOf(messages),
                rawPrompt.toString()
        );
    }

    private static Integer extractMaxTokens(ObjectNode root) {
        if (root.hasNonNull("max_completion_tokens") && root.get("max_completion_tokens").isIntegralNumber()) {
            return root.get("max_completion_tokens").asInt();
        }
        if (root.hasNonNull("max_tokens") && root.get("max_tokens").isIntegralNumber()) {
            return root.get("max_tokens").asInt();
        }
        return null;
    }

    private static TokenUsage parseUsageNode(JsonNode usage) {
        int prompt = Math.max(0, usage.path("prompt_tokens").asInt(0));
        int completion = Math.max(0, usage.path("completion_tokens").asInt(0));
        int cachedRead = Math.max(0, usage.path("prompt_tokens_details").path("cached_tokens").asInt(0));
        int cachedWrite = Math.max(0, usage.path("prompt_tokens_details").path("cache_write_tokens").asInt(0));
        int reasoning = Math.max(0, usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0));
        return new TokenUsage(prompt, cachedRead, cachedWrite, completion, reasoning, false);
    }

    private final class OpenAiStreamTracker implements StreamUsageTracker {

        private final CanonicalChatRequest request;
        private final SseEventSplitter splitter = new SseEventSplitter();
        private final StringBuilder streamedText = new StringBuilder();
        private TokenUsage observedUsage;

        private OpenAiStreamTracker(CanonicalChatRequest request) {
            this.request = request;
        }

        @Override
        public List<byte[]> feed(byte[] chunk) {
            List<SseEventSplitter.SseEvent> events = splitter.push(chunk);
            return processEvents(events);
        }

        @Override
        public List<byte[]> complete() {
            return processEvents(splitter.flushRemainder());
        }

        @Override
        public TokenUsage finalizeUsage(boolean cleanCompletion) {
            if (cleanCompletion && observedUsage != null) {
                return observedUsage;
            }
            if (observedUsage != null && observedUsage.completionTokens() > 0) {
                return observedUsage;
            }
            int estPrompt = (observedUsage != null && observedUsage.promptTokens() > 0)
                    ? observedUsage.promptTokens()
                    : tokenEstimator.estimatePromptTokens(request);
            int estCompletion = tokenEstimator.estimateCompletionTokens(request.model(), streamedText);
            return new TokenUsage(
                    estPrompt,
                    observedUsage == null ? 0 : observedUsage.cachedReadTokens(),
                    observedUsage == null ? 0 : observedUsage.cachedWriteTokens(),
                    estCompletion,
                    observedUsage == null ? 0 : observedUsage.reasoningTokens(),
                    true
            );
        }

        private List<byte[]> processEvents(List<SseEventSplitter.SseEvent> events) {
            if (events.isEmpty()) {
                return List.of();
            }
            List<byte[]> out = new ArrayList<>(events.size());
            for (SseEventSplitter.SseEvent event : events) {
                byte[] inspected = inspectAndFilter(event);
                if (inspected != null) {
                    out.add(inspected);
                }
            }
            return out;
        }

        private byte[] inspectAndFilter(SseEventSplitter.SseEvent event) {
            String data = event.dataPayload();
            if (data.isEmpty() || "[DONE]".equals(data)) {
                return event.rawBytes();
            }
            try {
                JsonNode node = objectMapper.readTree(data);
                if (!node.isObject()) {
                    return event.rawBytes();
                }
                accumulateChoices(node.get("choices"));
                JsonNode usageNode = node.get("usage");
                if (usageNode != null && usageNode.isObject()) {
                    observedUsage = parseUsageNode(usageNode);
                    if (!request.clientRequestedUsage()) {
                        if (isUsageOnlyFrame(node)) {
                            return null;
                        }
                        if (node instanceof ObjectNode obj) {
                            obj.remove("usage");
                            return SseEventSplitter.buildDataFrame(objectMapper.writeValueAsString(obj));
                        }
                    }
                }
                return event.rawBytes();
            } catch (Exception ignored) {
                return event.rawBytes();
            }
        }

        private void accumulateChoices(JsonNode choices) {
            if (choices == null || !choices.isArray()) {
                return;
            }
            for (JsonNode choice : choices) {
                JsonNode delta = choice.get("delta");
                if (delta == null || !delta.isObject()) {
                    continue;
                }
                if (delta.hasNonNull("content") && delta.get("content").isTextual()) {
                    ProtocolSupport.appendBounded(streamedText, delta.get("content").asText());
                }
                if (delta.hasNonNull("reasoning_content") && delta.get("reasoning_content").isTextual()) {
                    ProtocolSupport.appendBounded(streamedText, delta.get("reasoning_content").asText());
                }
            }
        }

        private boolean isUsageOnlyFrame(JsonNode root) {
            JsonNode choices = root.get("choices");
            if (choices == null || choices.isNull() || (choices.isArray() && choices.isEmpty())) {
                return true;
            }
            if (!choices.isArray()) {
                return false;
            }
            for (JsonNode choice : choices) {
                if (choice.hasNonNull("finish_reason")) {
                    return false;
                }
                JsonNode delta = choice.get("delta");
                if (delta != null && delta.isObject() && !delta.isEmpty()) {
                    return false;
                }
            }
            return true;
        }
    }
}

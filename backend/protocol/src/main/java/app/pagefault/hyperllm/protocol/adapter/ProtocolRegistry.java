package app.pagefault.hyperllm.protocol.adapter;

import app.pagefault.hyperllm.protocol.ir.ProtocolKind;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registry of supported wire protocol adapters.
 */
public final class ProtocolRegistry {

    private final Map<ProtocolKind, ProtocolAdapter> adapters = new EnumMap<>(ProtocolKind.class);

    public ProtocolRegistry(ObjectMapper objectMapper) {
        this(objectMapper, new TokenEstimator());
    }

    public ProtocolRegistry(ObjectMapper objectMapper, TokenEstimator tokenEstimator) {
        OpenAiChatProtocolAdapter openAi = new OpenAiChatProtocolAdapter(objectMapper, tokenEstimator);
        AnthropicMessagesProtocolAdapter anthropic = new AnthropicMessagesProtocolAdapter(objectMapper, tokenEstimator);
        adapters.put(openAi.kind(), openAi);
        adapters.put(anthropic.kind(), anthropic);
    }

    public ProtocolAdapter adapterFor(ProtocolKind kind) {
        ProtocolAdapter adapter = adapters.get(kind);
        if (adapter == null) {
            throw new IllegalArgumentException("Unsupported protocol kind: " + kind);
        }
        return adapter;
    }
}

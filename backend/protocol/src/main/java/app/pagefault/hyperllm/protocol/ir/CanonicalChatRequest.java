package app.pagefault.hyperllm.protocol.ir;

import java.util.List;

/**
 * Canonical representation extracted from an inbound chat/messages payload.
 */
public record CanonicalChatRequest(
        ProtocolKind inboundProtocol,
        String model,
        boolean stream,
        Integer maxTokens,
        boolean clientRequestedUsage,
        List<CanonicalMessage> messages,
        String rawPromptText
) {
}

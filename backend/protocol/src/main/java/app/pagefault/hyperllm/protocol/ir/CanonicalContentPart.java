package app.pagefault.hyperllm.protocol.ir;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Exhaustive set of multimodal, reasoning, tool, and refusal blocks inside a turn or response.
 */
public sealed interface CanonicalContentPart permits
        CanonicalContentPart.TextPart,
        CanonicalContentPart.ImagePart,
        CanonicalContentPart.ThinkingPart,
        CanonicalContentPart.RedactedThinkingPart,
        CanonicalContentPart.ToolCallPart,
        CanonicalContentPart.ToolResultPart,
        CanonicalContentPart.RefusalPart {

    record TextPart(
            String text,
            CacheControl cacheControl
    ) implements CanonicalContentPart {
        public TextPart {
            text = text == null ? "" : text;
        }

        public TextPart(String text) {
            this(text, null);
        }
    }

    record ImagePart(
            ImageSource source,
            ImageDetail detail,
            CacheControl cacheControl
    ) implements CanonicalContentPart {
        public ImagePart {
            detail = detail == null ? ImageDetail.AUTO : detail;
        }
    }

    record ThinkingPart(
            String text,
            String signature,
            String encryptedContent,
            ProtocolKind originProtocol,
            String wireField
    ) implements CanonicalContentPart {
        public ThinkingPart {
            text = text == null ? "" : text;
        }

        public ThinkingPart(
                String text,
                String signature,
                String encryptedContent,
                ProtocolKind originProtocol
        ) {
            this(text, signature, encryptedContent, originProtocol, null);
        }
    }

    record RedactedThinkingPart(
            String opaqueData,
            ProtocolKind originProtocol
    ) implements CanonicalContentPart {
    }

    record ToolCallPart(
            String callId,
            String itemId,
            String name,
            JsonNode argumentsJson,
            String rawArgumentsJson,
            String thoughtSignature,
            ProtocolKind originProtocol
    ) implements CanonicalContentPart {
    }

    record ToolResultPart(
            String callId,
            String toolName,
            List<CanonicalContentPart> content,
            boolean isError,
            CacheControl cacheControl
    ) implements CanonicalContentPart {
        public ToolResultPart {
            content = content == null ? List.of() : List.copyOf(content);
        }
    }

    record RefusalPart(
            String refusal
    ) implements CanonicalContentPart {
        public RefusalPart {
            refusal = refusal == null ? "" : refusal;
        }
    }
}

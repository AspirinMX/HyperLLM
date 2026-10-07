package app.pagefault.hyperllm.protocol.adapter;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.protocol.ir.CanonicalMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;

/**
 * Shared JSON parsing, message text extraction, and bounded text accumulation helpers.
 */
final class ProtocolSupport {

    static final int MAX_ACCUMULATED_CHARS = 1_000_000;

    private ProtocolSupport() {
    }

    static ObjectNode parseObjectRoot(ObjectMapper mapper, byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Request body must not be empty");
        }
        try {
            JsonNode tree = mapper.readTree(rawBody);
            if (!(tree instanceof ObjectNode obj)) {
                throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Request body must be a JSON object");
            }
            return obj;
        } catch (IOException ex) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Malformed JSON request body", ex);
        }
    }

    static String requireModel(ObjectNode root) {
        JsonNode modelNode = root.get("model");
        if (modelNode == null || !modelNode.isTextual() || modelNode.asText().isBlank()) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "Field 'model' is required and must be non-blank");
        }
        return modelNode.asText().trim();
    }

    static void rewriteModelIfPresent(ObjectNode root, String upstreamModelName) {
        if (upstreamModelName != null && !upstreamModelName.isBlank()) {
            root.put("model", upstreamModelName);
        }
    }

    static byte[] writeJsonBytes(ObjectMapper mapper, JsonNode node) {
        try {
            return mapper.writeValueAsBytes(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize upstream request JSON", ex);
        }
    }

    static String extractTextContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : contentNode) {
                String text = extractPartText(part);
                if (!text.isEmpty()) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(text);
                }
            }
            return sb.toString();
        }
        return contentNode.toString();
    }

    static void appendMessages(JsonNode messagesNode, List<CanonicalMessage> out, StringBuilder rawPromptOut) {
        if (messagesNode == null || !messagesNode.isArray()) {
            return;
        }
        for (JsonNode msg : messagesNode) {
            if (!msg.isObject()) {
                continue;
            }
            String role = msg.path("role").asText("user");
            String text = extractTextContent(msg.get("content"));
            out.add(new CanonicalMessage(role, text));
            if (!text.isEmpty()) {
                if (!rawPromptOut.isEmpty()) {
                    rawPromptOut.append('\n');
                }
                rawPromptOut.append(text);
            }
        }
    }

    static void appendBounded(StringBuilder sb, String fragment) {
        if (fragment == null || fragment.isEmpty() || sb.length() >= MAX_ACCUMULATED_CHARS) {
            return;
        }
        int remaining = MAX_ACCUMULATED_CHARS - sb.length();
        if (fragment.length() <= remaining) {
            sb.append(fragment);
        } else {
            sb.append(fragment, 0, remaining);
        }
    }

    private static String extractPartText(JsonNode part) {
        if (part == null || part.isNull()) {
            return "";
        }
        if (part.isTextual()) {
            return part.asText();
        }
        if (part.isObject()) {
            if (part.hasNonNull("text") && part.get("text").isTextual()) {
                return part.get("text").asText();
            }
            if (part.hasNonNull("thinking") && part.get("thinking").isTextual()) {
                return part.get("thinking").asText();
            }
            if (part.hasNonNull("content")) {
                return extractTextContent(part.get("content"));
            }
        }
        return "";
    }
}

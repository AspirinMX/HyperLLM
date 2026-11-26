package app.pagefault.hyperllm.protocol.ir;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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

    final class TextPart implements CanonicalContentPart {
        private final JsonUtf8Text utf8Text;
        private final CacheControl cacheControl;

        public TextPart(String text, CacheControl cacheControl) {
            this(JsonUtf8Text.ofString(text), cacheControl);
        }

        public TextPart(String text) {
            this(JsonUtf8Text.ofString(text), null);
        }

        private TextPart(JsonUtf8Text utf8Text, CacheControl cacheControl) {
            this.utf8Text = utf8Text != null ? utf8Text : JsonUtf8Text.EMPTY;
            this.cacheControl = cacheControl;
        }

        public static TextPart ofUtf8(JsonUtf8Text utf8Text) {
            return new TextPart(utf8Text, null);
        }

        public static TextPart ofUtf8(JsonUtf8Text utf8Text, CacheControl cacheControl) {
            return new TextPart(utf8Text, cacheControl);
        }

        public JsonUtf8Text utf8Text() {
            return utf8Text;
        }

        public String text() {
            return utf8Text.asString();
        }

        public CacheControl cacheControl() {
            return cacheControl;
        }

        public boolean isEmpty() {
            return utf8Text.isEmpty();
        }

        public int estimatedTokens() {
            return utf8Text.estimatedTokens();
        }

        public int estimatedWireBytes() {
            return utf8Text.estimatedWireBytes();
        }

        public void writeJsonValue(JsonGenerator gen) throws IOException {
            utf8Text.writeJsonValue(gen);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof TextPart other) {
                return Objects.equals(utf8Text, other.utf8Text)
                        && Objects.equals(cacheControl, other.cacheControl);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(utf8Text, cacheControl);
        }

        @Override
        public String toString() {
            return "TextPart[text=" + text() + ", cacheControl=" + cacheControl + "]";
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

    final class ThinkingPart implements CanonicalContentPart {
        private final JsonUtf8Text utf8Text;
        private final String signature;
        private final String encryptedContent;
        private final ProtocolKind originProtocol;
        private final String wireField;

        public ThinkingPart(
                String text,
                String signature,
                String encryptedContent,
                ProtocolKind originProtocol,
                String wireField
        ) {
            this(JsonUtf8Text.ofString(text), signature, encryptedContent, originProtocol, wireField);
        }

        public ThinkingPart(
                String text,
                String signature,
                String encryptedContent,
                ProtocolKind originProtocol
        ) {
            this(JsonUtf8Text.ofString(text), signature, encryptedContent, originProtocol, null);
        }

        private ThinkingPart(
                JsonUtf8Text utf8Text,
                String signature,
                String encryptedContent,
                ProtocolKind originProtocol,
                String wireField
        ) {
            this.utf8Text = utf8Text != null ? utf8Text : JsonUtf8Text.EMPTY;
            this.signature = signature;
            this.encryptedContent = encryptedContent;
            this.originProtocol = originProtocol;
            this.wireField = wireField;
        }

        public static ThinkingPart ofUtf8(
                JsonUtf8Text utf8Text,
                String signature,
                String encryptedContent,
                ProtocolKind originProtocol,
                String wireField
        ) {
            return new ThinkingPart(utf8Text, signature, encryptedContent, originProtocol, wireField);
        }

        public static ThinkingPart ofUtf8(
                JsonUtf8Text utf8Text,
                String signature,
                String encryptedContent,
                ProtocolKind originProtocol
        ) {
            return new ThinkingPart(utf8Text, signature, encryptedContent, originProtocol, null);
        }

        public JsonUtf8Text utf8Text() {
            return utf8Text;
        }

        public String text() {
            return utf8Text.asString();
        }

        public String signature() {
            return signature;
        }

        public String encryptedContent() {
            return encryptedContent;
        }

        public ProtocolKind originProtocol() {
            return originProtocol;
        }

        public String wireField() {
            return wireField;
        }

        public boolean isEmpty() {
            return utf8Text.isEmpty();
        }

        public int estimatedTokens() {
            return utf8Text.estimatedTokens();
        }

        public int estimatedWireBytes() {
            return utf8Text.estimatedWireBytes();
        }

        public void writeJsonValue(JsonGenerator gen) throws IOException {
            utf8Text.writeJsonValue(gen);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof ThinkingPart other) {
                return Objects.equals(utf8Text, other.utf8Text)
                        && Objects.equals(signature, other.signature)
                        && Objects.equals(encryptedContent, other.encryptedContent)
                        && originProtocol == other.originProtocol
                        && Objects.equals(wireField, other.wireField);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(utf8Text, signature, encryptedContent, originProtocol, wireField);
        }

        @Override
        public String toString() {
            return "ThinkingPart[text=" + text() + ", originProtocol=" + originProtocol + ", wireField=" + wireField + "]";
        }
    }

    record RedactedThinkingPart(
            String opaqueData,
            ProtocolKind originProtocol
    ) implements CanonicalContentPart {
    }

    final class ToolCallPart implements CanonicalContentPart {
        private final String callId;
        private final String itemId;
        private final String name;
        private final JsonNode argumentsJson;
        private final ValidatedJsonObject validatedArguments;
        private final String rawArgumentsJson;
        private final JsonUtf8Text rawArgumentsUtf8;
        private final String thoughtSignature;
        private final ProtocolKind originProtocol;

        public ToolCallPart(
                String callId,
                String itemId,
                String name,
                JsonNode argumentsJson,
                String rawArgumentsJson,
                String thoughtSignature,
                ProtocolKind originProtocol
        ) {
            this(callId, itemId, name, argumentsJson, null, rawArgumentsJson, null, thoughtSignature, originProtocol);
        }

        private ToolCallPart(
                String callId,
                String itemId,
                String name,
                JsonNode argumentsJson,
                ValidatedJsonObject validatedArguments,
                String rawArgumentsJson,
                JsonUtf8Text rawArgumentsUtf8,
                String thoughtSignature,
                ProtocolKind originProtocol
        ) {
            this.callId = callId;
            this.itemId = itemId;
            this.name = name;
            this.argumentsJson = argumentsJson;
            this.validatedArguments = validatedArguments;
            this.rawArgumentsJson = rawArgumentsJson;
            this.rawArgumentsUtf8 = rawArgumentsUtf8;
            this.thoughtSignature = thoughtSignature;
            this.originProtocol = originProtocol;
        }

        public static ToolCallPart ofRawUtf8Args(
                String callId,
                String itemId,
                String name,
                JsonUtf8Text rawArgumentsUtf8,
                String thoughtSignature,
                ProtocolKind originProtocol
        ) {
            return new ToolCallPart(callId, itemId, name, null, null, null, rawArgumentsUtf8, thoughtSignature, originProtocol);
        }

        public static ToolCallPart ofValidatedObjectArgs(
                String callId,
                String itemId,
                String name,
                ValidatedJsonObject validatedArguments,
                String thoughtSignature,
                ProtocolKind originProtocol
        ) {
            return new ToolCallPart(callId, itemId, name, null, validatedArguments, null, null, thoughtSignature, originProtocol);
        }

        public ToolCallPart withSanitizedCallIdAndSignature(String newCallId, String newThoughtSignature) {
            return new ToolCallPart(
                    newCallId, itemId, name, argumentsJson, validatedArguments,
                    rawArgumentsJson, rawArgumentsUtf8, newThoughtSignature, originProtocol
            );
        }

        public String callId() {
            return callId;
        }

        public String itemId() {
            return itemId;
        }

        public String name() {
            return name;
        }

        public JsonNode argumentsJson() {
            if (argumentsJson != null) {
                return argumentsJson;
            }
            if (validatedArguments != null) {
                return validatedArguments.asJsonNode();
            }
            return null;
        }

        public ValidatedJsonObject validatedArguments() {
            return validatedArguments;
        }

        public String rawArgumentsJson() {
            if (rawArgumentsJson != null) {
                return rawArgumentsJson;
            }
            if (rawArgumentsUtf8 != null) {
                return rawArgumentsUtf8.asString();
            }
            return null;
        }

        public JsonUtf8Text rawArgumentsUtf8() {
            return rawArgumentsUtf8;
        }

        public String thoughtSignature() {
            return thoughtSignature;
        }

        public ProtocolKind originProtocol() {
            return originProtocol;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof ToolCallPart other) {
                return Objects.equals(callId, other.callId)
                        && Objects.equals(itemId, other.itemId)
                        && Objects.equals(name, other.name)
                        && Objects.equals(argumentsJson(), other.argumentsJson())
                        && Objects.equals(rawArgumentsJson(), other.rawArgumentsJson())
                        && Objects.equals(thoughtSignature, other.thoughtSignature)
                        && originProtocol == other.originProtocol;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(callId, itemId, name, argumentsJson(), rawArgumentsJson(), thoughtSignature, originProtocol);
        }

        @Override
        public String toString() {
            return "ToolCallPart[callId=" + callId + ", name=" + name + ", originProtocol=" + originProtocol + "]";
        }
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

        public static ToolResultPart ofOwned(
                String callId,
                String toolName,
                List<CanonicalContentPart> ownedContent,
                boolean isError,
                CacheControl cacheControl
        ) {
            // Note: record canonical constructor still runs List.copyOf; kept small (1-4 elements).
            return new ToolResultPart(
                    callId,
                    toolName,
                    ownedContent == null ? List.of() : Collections.unmodifiableList(ownedContent),
                    isError,
                    cacheControl
            );
        }
    }

    final class RefusalPart implements CanonicalContentPart {
        private final JsonUtf8Text utf8Text;

        public RefusalPart(String refusal) {
            this(JsonUtf8Text.ofString(refusal));
        }

        private RefusalPart(JsonUtf8Text utf8Text) {
            this.utf8Text = utf8Text != null ? utf8Text : JsonUtf8Text.EMPTY;
        }

        public static RefusalPart ofUtf8(JsonUtf8Text utf8Text) {
            return new RefusalPart(utf8Text);
        }

        public JsonUtf8Text utf8Text() {
            return utf8Text;
        }

        public String refusal() {
            return utf8Text.asString();
        }

        public boolean isEmpty() {
            return utf8Text.isEmpty();
        }

        public int estimatedTokens() {
            return utf8Text.estimatedTokens();
        }

        public int estimatedWireBytes() {
            return utf8Text.estimatedWireBytes();
        }

        public void writeJsonValue(JsonGenerator gen) throws IOException {
            utf8Text.writeJsonValue(gen);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof RefusalPart other) {
                return Objects.equals(utf8Text, other.utf8Text);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(utf8Text);
        }

        @Override
        public String toString() {
            return "RefusalPart[refusal=" + refusal() + "]";
        }
    }
}

package app.pagefault.hyperllm.protocol.ir;

import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Validated JSON object stored as raw UTF-8 bytes rather than a Jackson {@code ObjectNode} DOM tree.
 *
 * <p>Used for tool parameter JSON schemas and structured tool-call arguments to eliminate
 * {@code ObjectNode} / {@code LinkedHashMap} allocations during decode, token estimation, and
 * re-encoding, while still strictly validating object syntax via Jackson's streaming
 * {@link JsonParser#skipChildren()}.
 */
public final class ValidatedJsonObject {

    private static final ObjectMapper FALLBACK_MAPPER = new ObjectMapper();

    public static final ValidatedJsonObject EMPTY_OBJECT_SCHEMA =
            ofRawUtf8("{\"type\":\"object\",\"properties\":{}}".getBytes(StandardCharsets.UTF_8));

    public static final ValidatedJsonObject EMPTY_OBJECT =
            ofRawUtf8("{}".getBytes(StandardCharsets.UTF_8));

    private final byte[] backing;
    private final int offset;
    private final int length;
    private final int estimatedTokens;
    private JsonNode cachedNode;
    private String cachedRawString;

    private ValidatedJsonObject(byte[] backing, int offset, int length, JsonNode cachedNode, int estimatedTokens) {
        this.backing = backing;
        this.offset = offset;
        this.length = length;
        this.cachedNode = cachedNode;
        this.estimatedTokens = estimatedTokens;
    }

    public static ValidatedJsonObject ofRawUtf8(byte[] utf8) {
        if (utf8 == null || utf8.length == 0) {
            return null;
        }
        return new ValidatedJsonObject(utf8, 0, utf8.length, null, estimateRawUtf8Tokens(utf8, 0, utf8.length));
    }

    public static ValidatedJsonObject ofJsonNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return new ValidatedJsonObject(null, 0, 0, node, -1);
    }

    public static ValidatedJsonObject readFromParser(
            JsonParser parser,
            ObjectMapper mapper,
            byte[] rawBacking
    ) throws IOException {
        return readFromParser(parser, mapper, rawBacking, 0, rawBacking != null ? rawBacking.length : 0);
    }

    /**
     * Reads and validates a JSON object at {@code parser}'s current {@link JsonToken#START_OBJECT}
     * position without building a DOM tree when backed by {@code rawBacking}.
     */
    public static ValidatedJsonObject readFromParser(
            JsonParser parser,
            ObjectMapper mapper,
            byte[] rawBacking,
            int baseOffset,
            int endOffset
    ) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return null;
        }
        if (rawBacking != null) {
            long relStart = parser.currentTokenLocation().getByteOffset();
            parser.skipChildren();
            long relEnd = parser.currentTokenLocation().getByteOffset();
            long absStart = baseOffset + relStart;
            long absEnd = baseOffset + relEnd;
            if (relStart >= 0L && absStart >= baseOffset && absEnd >= absStart && absEnd < endOffset) {
                int start = (int) absStart;
                int end = (int) absEnd;
                if (rawBacking[start] == '{' && rawBacking[end] == '}') {
                    int len = end - start + 1;
                    int tokens = estimateRawUtf8Tokens(rawBacking, start, len);
                    return new ValidatedJsonObject(rawBacking, start, len, null, tokens);
                }
            }
            return EMPTY_OBJECT;
        }
        JsonNode node = mapper.readTree(parser);
        return (node != null && node.isObject()) ? ofJsonNode(node) : null;
    }

    public boolean isSliceBacked() {
        return backing != null && length > 0;
    }

    public int estimatedWireBytes() {
        return backing != null ? length : 256;
    }

    public int estimatedTokens() {
        if (estimatedTokens >= 0) {
            return estimatedTokens;
        }
        if (cachedNode != null) {
            TokenEstimator.TextTokenCounter c = new TokenEstimator.TextTokenCounter();
            c.add(cachedNode.toString());
            return c.estimateTokens();
        }
        return 0;
    }

    /** Lazy DOM accessor for cold callers and tests inspecting {@code parametersSchema()}. */
    public JsonNode asJsonNode() {
        return asJsonNode(FALLBACK_MAPPER);
    }

    public JsonNode asJsonNode(ObjectMapper mapper) {
        if (cachedNode != null) {
            return cachedNode;
        }
        if (backing == null || length == 0) {
            return null;
        }
        ObjectMapper m = mapper != null ? mapper : FALLBACK_MAPPER;
        try (JsonParser p = m.getFactory().createParser(backing, offset, length)) {
            JsonNode parsed = m.readTree(p);
            cachedNode = parsed;
            return parsed;
        } catch (IOException ex) {
            throw new IllegalStateException("ValidatedJsonObject bytes failed to parse", ex);
        }
    }

    public void writeRawObject(JsonGenerator gen, ObjectMapper mapper) throws IOException {
        if (backing != null && length > 0) {
            gen.writeRawValue(new RawUtf8Serializable(backing, offset, length));
            return;
        }
        if (cachedNode != null) {
            mapper.writeTree(gen, cachedNode);
            return;
        }
        gen.writeRawValue("{\"type\":\"object\",\"properties\":{}}");
    }

    public String rawString() {
        if (cachedRawString != null) {
            return cachedRawString;
        }
        if (backing != null) {
            cachedRawString = new String(backing, offset, length, StandardCharsets.UTF_8);
            return cachedRawString;
        }
        if (cachedNode != null) {
            cachedRawString = cachedNode.toString();
            return cachedRawString;
        }
        return "{}";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof ValidatedJsonObject other) {
            return Objects.equals(asJsonNode(), other.asJsonNode());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(asJsonNode());
    }

    @Override
    public String toString() {
        return rawString();
    }

    private static int estimateRawUtf8Tokens(byte[] buf, int off, int len) {
        long codePoints = 0L;
        int end = off + len;
        for (int i = off; i < end; i++) {
            // In valid UTF-8, continuation bytes match 10xxxxxx (0x80..0xBF); every other byte starts a code point.
            if ((buf[i] & 0xC0) != 0x80) {
                codePoints++;
            }
        }
        return TokenEstimator.estimateFromCounts(codePoints, len);
    }

    /**
     * Zero-allocation Jackson {@link SerializableString} view over a raw UTF-8 JSON object slice.
     */
    private static final class RawUtf8Serializable implements SerializableString {
        private final byte[] backing;
        private final int offset;
        private final int length;
        private SerializedString fallback;

        private RawUtf8Serializable(byte[] backing, int offset, int length) {
            this.backing = backing;
            this.offset = offset;
            this.length = length;
        }

        private SerializedString fallback() {
            if (fallback == null) {
                fallback = new SerializedString(new String(backing, offset, length, StandardCharsets.UTF_8));
            }
            return fallback;
        }

        @Override
        public String getValue() {
            return fallback().getValue();
        }

        @Override
        public int charLength() {
            return fallback().charLength();
        }

        @Override
        public char[] asQuotedChars() {
            return fallback().asQuotedChars();
        }

        @Override
        public byte[] asUnquotedUTF8() {
            if (offset == 0 && length == backing.length) {
                return backing;
            }
            byte[] copy = new byte[length];
            System.arraycopy(backing, offset, copy, 0, length);
            return copy;
        }

        @Override
        public byte[] asQuotedUTF8() {
            return fallback().asQuotedUTF8();
        }

        @Override
        public int appendQuotedUTF8(byte[] buffer, int off) {
            return fallback().appendQuotedUTF8(buffer, off);
        }

        @Override
        public int appendQuoted(char[] buffer, int off) {
            return fallback().appendQuoted(buffer, off);
        }

        @Override
        public int appendUnquotedUTF8(byte[] buffer, int off) {
            if (off + length > buffer.length) {
                return -1;
            }
            System.arraycopy(backing, offset, buffer, off, length);
            return length;
        }

        @Override
        public int appendUnquoted(char[] buffer, int off) {
            return fallback().appendUnquoted(buffer, off);
        }

        @Override
        public int writeQuotedUTF8(java.io.OutputStream out) throws IOException {
            return fallback().writeQuotedUTF8(out);
        }

        @Override
        public int writeUnquotedUTF8(java.io.OutputStream out) throws IOException {
            out.write(backing, offset, length);
            return length;
        }

        @Override
        public int putQuotedUTF8(java.nio.ByteBuffer buffer) throws IOException {
            return fallback().putQuotedUTF8(buffer);
        }

        @Override
        public int putUnquotedUTF8(java.nio.ByteBuffer buffer) throws IOException {
            if (length > buffer.remaining()) {
                return -1;
            }
            buffer.put(backing, offset, length);
            return length;
        }
    }
}

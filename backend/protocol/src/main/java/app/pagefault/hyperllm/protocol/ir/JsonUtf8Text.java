package app.pagefault.hyperllm.protocol.ir;

import app.pagefault.hyperllm.protocol.io.ByteSink;
import app.pagefault.hyperllm.protocol.io.JsonUtf8Writer;
import app.pagefault.hyperllm.protocol.tokenizer.TokenEstimator;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Immutable leaf text value inside the Canonical IR that can either reference a strictly validated
 * UTF-8 JSON string slice from the inbound request buffer or hold a materialized Java {@link String}.
 *
 * <p>Security invariant: a {@link ValidatedJsonSlice} is created ONLY when the byte range between
 * the JSON string quotes has been verified byte-for-byte to be valid RFC 3629 UTF-8 with exclusively
 * canonical single-character RFC 8259 escapes ({@code \"}, {@code \\}, {@code \/}, {@code \b},
 * {@code \f}, {@code \n}, {@code \r}, {@code \t}) and no raw control bytes ({@code < 0x20}).
 * Any {@code \u005CuXXXX} escape, surrogate sequence, or non-array source falls back to Jackson's standard
 * {@link JsonParser#getText()} decoding and canonical re-encoding.
 *
 * <p>Because all four supported LLM protocols (OpenAI Chat, OpenAI Responses, Anthropic Messages,
 * Google Gemini) serialize text leaves as RFC 8259 UTF-8 JSON strings, validated slices are safely
 * reusable across both same-protocol and cross-protocol encoding without UTF-8 &rarr; UTF-16 &rarr;
 * UTF-8 round-trips or per-turn {@link String} allocations, while every JSON structural token
 * (objects, arrays, keys, roles, numbers, booleans) is always rebuilt from scratch by the outbound
 * codec.
 */
public sealed interface JsonUtf8Text permits
        JsonUtf8Text.ValidatedJsonSlice,
        JsonUtf8Text.MaterializedString {

    JsonUtf8Text EMPTY = new MaterializedString("");

    boolean isEmpty();

    /** Raw UTF-8 wire length inside JSON quotes (without surrounding quotes). */
    int estimatedWireBytes();

    /** Precomputed token estimate matching {@link TokenEstimator#countText(String, CharSequence)}. */
    int estimatedTokens();

    /** Decoded Java {@link String} (lazy on {@link ValidatedJsonSlice}). */
    String asString();

    /** Writes this value as a JSON string directly to a Jackson {@link JsonGenerator}. */
    void writeJsonValue(JsonGenerator gen) throws IOException;

    /** Writes this value as a JSON string directly to a {@link ByteSink}. */
    void writeJsonValue(ByteSink out);

    static JsonUtf8Text ofString(String text) {
        if (text == null || text.isEmpty()) {
            return EMPTY;
        }
        return new MaterializedString(text);
    }

    /**
     * Reads the current {@link JsonToken#VALUE_STRING} from {@code parser} as a zero-copy
     * {@link ValidatedJsonSlice} whenever possible, computing exact token counts in the same pass.
     * Never calls {@link JsonParser#getText()} on the fast path, so Jackson skips buffering the
     * string into {@code char[]}/{@code String}.
     */
    static JsonUtf8Text readFromParser(JsonParser parser, byte[] rawBacking) throws IOException {
        return readFromParser(parser, rawBacking, 0, rawBacking != null ? rawBacking.length : 0);
    }

    static JsonUtf8Text readFromParser(
            JsonParser parser,
            byte[] rawBacking,
            int baseOffset,
            int endOffset
    ) throws IOException {
        if (rawBacking != null) {
            long relQuotePos = parser.currentTokenLocation().getByteOffset();
            if (relQuotePos >= 0L) {
                long absQuotePos = baseOffset + relQuotePos;
                if (absQuotePos >= baseOffset && absQuotePos < endOffset) {
                    int quotePos = (int) absQuotePos;
                    if (rawBacking[quotePos] == '"') {
                        ValidatedJsonSlice slice = scanValidatedSlice(rawBacking, quotePos + 1, endOffset);
                        if (slice != null) {
                            return slice;
                        }
                    }
                }
            }
        }
        return ofString(parser.getText());
    }

    /**
     * Reads a nullable string field at {@code parser}'s current token position without materializing
     * when backed by {@code rawBacking}. Non-string non-null values are skipped and return {@code null}.
     */
    static JsonUtf8Text readNullableFromParser(
            JsonParser parser,
            byte[] rawBacking,
            int baseOffset,
            int endOffset
    ) throws IOException {
        JsonToken t = parser.currentToken();
        if (t == JsonToken.VALUE_STRING) {
            return readFromParser(parser, rawBacking, baseOffset, endOffset);
        }
        if (t == JsonToken.VALUE_NULL) {
            return null;
        }
        parser.skipChildren();
        return null;
    }

    /**
     * Validates strict UTF-8 + canonical RFC 8259 escapes from {@code start} up to the closing
     * {@code '"'} within {@code [start, end)} and counts exact decoded code points and UTF-8 bytes
     * in one pass. Uses an 8-byte SWAR (SIMD Within A Register) fast path for runs of unescaped
     * printable ASCII ({@code 0x20..0x7F \ {'"', '\\'}}) before dropping to the scalar validator
     * for quotes, escapes, control bytes, and multi-byte UTF-8 sequences. Returns {@code null} to
     * trigger standard Jackson {@code parser.getText()} fallback whenever any unicode escape,
     * invalid escape, unescaped control byte, or malformed UTF-8 is encountered.
     */
    static ValidatedJsonSlice scanValidatedSlice(byte[] buf, int start, int end) {
        int i = start;
        int swarLimit = end - 8;
        long codePoints = 0L;
        long decodedUtf8Bytes = 0L;
        boolean hasEscapes = false;

        while (i < end) {
            // SWAR 8-byte fast path: when (word & 0x80..80) == 0 every lane is in 0x00..0x7F, so
            // (word + 0x60..60) cannot carry across lanes and has bit 7 set iff lane >= 0x20.
            // Combined with Mycroft zero-byte tests for '"' (0x22) and '\\' (0x5C), special == 0L
            // iff all 8 bytes are safe unescaped printable ASCII JSON string characters.
            while (i <= swarLimit) {
                long word = (long) Swar.LONG_LE.get(buf, i);
                long q = word ^ 0x2222222222222222L;
                long bs = word ^ 0x5C5C5C5C5C5C5C5CL;
                long special = (word & 0x8080808080808080L)
                        | (~(word + 0x6060606060606060L) & 0x8080808080808080L)
                        | ((q - 0x0101010101010101L) & ~q & 0x8080808080808080L)
                        | ((bs - 0x0101010101010101L) & ~bs & 0x8080808080808080L);
                if (special != 0L) {
                    break;
                }
                codePoints += 8L;
                decodedUtf8Bytes += 8L;
                i += 8;
            }
            if (i >= end) {
                break;
            }
            byte b = buf[i];
            if (b == '"') {
                int sliceLen = i - start;
                if (sliceLen == 0) {
                    return ValidatedJsonSlice.EMPTY_SLICE;
                }
                int estTokens = TokenEstimator.estimateFromCounts(codePoints, decodedUtf8Bytes);
                return new ValidatedJsonSlice(buf, start, sliceLen, hasEscapes, estTokens);
            }
            if (b == '\\') {
                hasEscapes = true;
                if (i + 1 >= end) {
                    return null;
                }
                byte esc = buf[i + 1];
                switch (esc) {
                    case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {
                        codePoints++;
                        decodedUtf8Bytes++;
                        i += 2;
                    }
                    // Any unicode escape (or unknown escape) falls back to Jackson for strict decoding & re-escaping.
                    default -> {
                        return null;
                    }
                }
                continue;
            }
            if (b >= 0x20) {
                // Fast ASCII path (most bytes in code/English prompts)
                codePoints++;
                decodedUtf8Bytes++;
                i++;
                continue;
            }
            if (b >= 0) {
                // Unescaped control character 0x00..0x1F is illegal in JSON strings; let Jackson reject it.
                return null;
            }
            // Strict RFC 3629 multi-byte UTF-8 validation
            int ub = b & 0xFF;
            if (ub >= 0xC2 && ub <= 0xDF) {
                if (i + 1 >= end || (buf[i + 1] & 0xC0) != 0x80) {
                    return null;
                }
                codePoints++;
                decodedUtf8Bytes += 2L;
                i += 2;
            } else if (ub >= 0xE0 && ub <= 0xEF) {
                if (i + 2 >= end) {
                    return null;
                }
                int b1 = buf[i + 1] & 0xFF;
                int b2 = buf[i + 2] & 0xFF;
                if ((b1 & 0xC0) != 0x80 || (b2 & 0xC0) != 0x80) {
                    return null;
                }
                if (ub == 0xE0 && b1 < 0xA0) {
                    return null; // overlong
                }
                if (ub == 0xED && b1 >= 0xA0) {
                    return null; // UTF-16 surrogate range U+D800..U+DFFF forbidden in UTF-8
                }
                codePoints++;
                decodedUtf8Bytes += 3L;
                i += 3;
            } else if (ub >= 0xF0 && ub <= 0xF4) {
                if (i + 3 >= end) {
                    return null;
                }
                int b1 = buf[i + 1] & 0xFF;
                int b2 = buf[i + 2] & 0xFF;
                int b3 = buf[i + 3] & 0xFF;
                if ((b1 & 0xC0) != 0x80 || (b2 & 0xC0) != 0x80 || (b3 & 0xC0) != 0x80) {
                    return null;
                }
                if (ub == 0xF0 && b1 < 0x90) {
                    return null; // overlong
                }
                if (ub == 0xF4 && b1 > 0x8F) {
                    return null; // > U+10FFFF
                }
                codePoints++;
                decodedUtf8Bytes += 4L;
                i += 4;
            } else {
                return null;
            }
        }
        // Unterminated string: let Jackson report the syntax error.
        return null;
    }

    /** Holder class so the VarHandle stays private to {@link JsonUtf8Text} (sealed interface permits only implementations). */
    final class Swar {
        static final VarHandle LONG_LE =
                MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

        private Swar() {
        }
    }

    final class ValidatedJsonSlice implements JsonUtf8Text {

        private static final ValidatedJsonSlice EMPTY_SLICE =
                new ValidatedJsonSlice(new byte[0], 0, 0, false, 0);

        private final byte[] backing;
        private final int offset;
        private final int length;
        private final boolean hasEscapes;
        private final int estimatedTokens;
        private String materialized;

        ValidatedJsonSlice(byte[] backing, int offset, int length, boolean hasEscapes, int estimatedTokens) {
            this.backing = backing;
            this.offset = offset;
            this.length = length;
            this.hasEscapes = hasEscapes;
            this.estimatedTokens = estimatedTokens;
        }

        public byte[] backing() {
            return backing;
        }

        public int offset() {
            return offset;
        }

        public int length() {
            return length;
        }

        @Override
        public boolean isEmpty() {
            return length == 0;
        }

        @Override
        public int estimatedWireBytes() {
            return length;
        }

        @Override
        public int estimatedTokens() {
            return estimatedTokens;
        }

        @Override
        public String asString() {
            String cached = materialized;
            if (cached != null) {
                return cached;
            }
            if (length == 0) {
                materialized = "";
                return "";
            }
            String decoded;
            if (!hasEscapes) {
                decoded = new String(backing, offset, length, StandardCharsets.UTF_8);
            } else {
                decoded = unescapeValidatedSlice(backing, offset, length);
            }
            materialized = decoded;
            return decoded;
        }

        @Override
        public void writeJsonValue(JsonGenerator gen) throws IOException {
            if (length == 0) {
                gen.writeString("");
                return;
            }
            gen.writeRawUTF8String(backing, offset, length);
        }

        @Override
        public void writeJsonValue(ByteSink out) {
            JsonUtf8Writer.writeRawJsonString(backing, offset, length, out);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof JsonUtf8Text other) {
                return asString().equals(other.asString());
            }
            return false;
        }

        @Override
        public int hashCode() {
            return asString().hashCode();
        }

        @Override
        public String toString() {
            return asString();
        }

        private static String unescapeValidatedSlice(byte[] src, int off, int len) {
            byte[] out = new byte[len];
            int w = 0;
            int end = off + len;
            for (int i = off; i < end; i++) {
                byte b = src[i];
                if (b == '\\' && i + 1 < end) {
                    byte next = src[++i];
                    out[w++] = switch (next) {
                        case '"' -> '"';
                        case '\\' -> '\\';
                        case '/' -> '/';
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        default -> next;
                    };
                } else {
                    out[w++] = b;
                }
            }
            return new String(out, 0, w, StandardCharsets.UTF_8);
        }
    }

    final class MaterializedString implements JsonUtf8Text {

        private final String value;
        private int cachedTokens = -1;

        MaterializedString(String value) {
            this.value = value == null ? "" : value;
        }

        @Override
        public boolean isEmpty() {
            return value.isEmpty();
        }

        @Override
        public int estimatedWireBytes() {
            return value.length();
        }

        @Override
        public int estimatedTokens() {
            int t = cachedTokens;
            if (t >= 0) {
                return t;
            }
            if (value.isEmpty()) {
                cachedTokens = 0;
                return 0;
            }
            TokenEstimator.TextTokenCounter c = new TokenEstimator.TextTokenCounter();
            c.add(value);
            t = c.estimateTokens();
            cachedTokens = t;
            return t;
        }

        @Override
        public String asString() {
            return value;
        }

        @Override
        public void writeJsonValue(JsonGenerator gen) throws IOException {
            gen.writeString(value);
        }

        @Override
        public void writeJsonValue(ByteSink out) {
            JsonUtf8Writer.writeJsonString(value, out);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof JsonUtf8Text other) {
                return value.equals(other.asString());
            }
            return false;
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }

        @Override
        public String toString() {
            return value;
        }
    }
}

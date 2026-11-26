package app.pagefault.hyperllm.protocol.io;

/**
 * Zero-allocation RFC 8259 UTF-8 JSON string escaper writing straight into a {@link ByteSink}.
 *
 * <p>Replaces per-frame {@code UTF8JsonGenerator} + {@code IOContext} + {@code JsonWriteContext}
 * instantiation on streaming delta frames while keeping strict RFC 8259 JSON escaping and valid
 * UTF-8 encoding (including supplementary surrogate pairs and control character escaping).
 */
public final class JsonUtf8Writer {

    private static final byte[] HEX = {
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    };

    private JsonUtf8Writer() {
    }

    /** Writes {@code "escaped-utf8"} directly into {@code out}. */
    public static void writeJsonString(CharSequence text, ByteSink out) {
        out.writeByte('"');
        if (text != null && !text.isEmpty()) {
            writeEscapedBody(text, out);
        }
        out.writeByte('"');
    }

    /** Writes a pre-validated raw JSON string slice as {@code "rawBytes"} directly into {@code out}. */
    public static void writeRawJsonString(byte[] backing, int offset, int length, ByteSink out) {
        out.writeByte('"');
        if (length > 0) {
            out.writeBytes(backing, offset, length);
        }
        out.writeByte('"');
    }

    /** Writes escaped JSON string contents (without surrounding quotes) into {@code out}. */
    public static void writeEscapedBody(CharSequence text, ByteSink out) {
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c >= 0x20 && c < 0x80) {
                if (c == '"' || c == '\\') {
                    out.writeByte('\\');
                }
                out.writeByte(c);
                continue;
            }
            if (c < 0x20) {
                writeControlEscape(c, out);
                continue;
            }
            if (c < 0x800) {
                out.writeByte(0xC0 | (c >> 6));
                out.writeByte(0x80 | (c & 0x3F));
                continue;
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < len) {
                    char low = text.charAt(i + 1);
                    if (Character.isLowSurrogate(low)) {
                        int cp = Character.toCodePoint(c, low);
                        out.writeByte(0xF0 | (cp >> 18));
                        out.writeByte(0x80 | ((cp >> 12) & 0x3F));
                        out.writeByte(0x80 | ((cp >> 6) & 0x3F));
                        out.writeByte(0x80 | (cp & 0x3F));
                        i++;
                        continue;
                    }
                }
                writeUnicodeEscape(c, out);
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                writeUnicodeEscape(c, out);
                continue;
            }
            out.writeByte(0xE0 | (c >> 12));
            out.writeByte(0x80 | ((c >> 6) & 0x3F));
            out.writeByte(0x80 | (c & 0x3F));
        }
    }

    private static void writeControlEscape(char c, ByteSink out) {
        switch (c) {
            case '\b' -> {
                out.writeByte('\\');
                out.writeByte('b');
            }
            case '\f' -> {
                out.writeByte('\\');
                out.writeByte('f');
            }
            case '\n' -> {
                out.writeByte('\\');
                out.writeByte('n');
            }
            case '\r' -> {
                out.writeByte('\\');
                out.writeByte('r');
            }
            case '\t' -> {
                out.writeByte('\\');
                out.writeByte('t');
            }
            default -> writeUnicodeEscape(c, out);
        }
    }

    private static void writeUnicodeEscape(char c, ByteSink out) {
        out.writeByte('\\');
        out.writeByte('u');
        out.writeByte(HEX[(c >>> 12) & 0xF]);
        out.writeByte(HEX[(c >>> 8) & 0xF]);
        out.writeByte(HEX[(c >>> 4) & 0xF]);
        out.writeByte(HEX[c & 0xF]);
    }
}

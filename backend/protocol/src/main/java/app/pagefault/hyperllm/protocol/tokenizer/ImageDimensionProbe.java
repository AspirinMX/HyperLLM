package app.pagefault.hyperllm.protocol.tokenizer;

import java.util.Base64;
import java.util.Optional;

/**
 * Zero-dependency image header probe that inspects base64-encoded PNG, JPEG, GIF, or WebP
 * headers without decoding full pixel buffers, enabling accurate multimodal Pre-hold token estimation.
 */
public final class ImageDimensionProbe {

    private static final int MAX_BASE64_PREFIX_CHARS = 88_000; // ~64 KB decoded prefix for EXIF-heavy JPEGs

    public record Dimensions(int width, int height) {
    }

    private ImageDimensionProbe() {
    }

    public static Optional<Dimensions> probeBase64(String base64Data) {
        if (base64Data == null || base64Data.length() < 16) {
            return Optional.empty();
        }
        try {
            String cleaned = stripWhitespacePrefix(base64Data, MAX_BASE64_PREFIX_CHARS);
            int usableLen = (cleaned.length() / 4) * 4;
            if (usableLen < 16) {
                return Optional.empty();
            }
            byte[] bytes = Base64.getDecoder().decode(cleaned.substring(0, usableLen));
            return probeBytes(bytes);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public static Optional<Dimensions> probeBytes(byte[] data) {
        if (data == null || data.length < 10) {
            return Optional.empty();
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A, IHDR width at [16..19], height at [20..23]
        if (data.length >= 24
                && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            int w = readInt32BE(data, 16);
            int h = readInt32BE(data, 20);
            return valid(w, h);
        }
        // GIF87a / GIF89a: width at [6..7] LE, height at [8..9] LE
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8'
                && (data[4] == '7' || data[4] == '9') && data[5] == 'a') {
            int w = readUInt16LE(data, 6);
            int h = readUInt16LE(data, 8);
            return valid(w, h);
        }
        // WebP: RIFF....WEBP
        if (data.length >= 30
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return probeWebp(data);
        }
        // JPEG: FF D8
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
            return probeJpeg(data);
        }
        return Optional.empty();
    }

    private static Optional<Dimensions> probeWebp(byte[] data) {
        String chunk = new String(data, 12, 4);
        if ("VP8X".equals(chunk) && data.length >= 30) {
            int w = 1 + readUInt24LE(data, 24);
            int h = 1 + readUInt24LE(data, 27);
            return valid(w, h);
        }
        if ("VP8L".equals(chunk) && data.length >= 25 && (data[20] & 0xFF) == 0x2F) {
            int bits = readInt32LE(data, 21);
            int w = 1 + (bits & 0x3FFF);
            int h = 1 + ((bits >>> 14) & 0x3FFF);
            return valid(w, h);
        }
        if ("VP8 ".equals(chunk) && data.length >= 30
                && (data[23] & 0xFF) == 0x9D && (data[24] & 0xFF) == 0x01 && (data[25] & 0xFF) == 0x2A) {
            int w = readUInt16LE(data, 26) & 0x3FFF;
            int h = readUInt16LE(data, 28) & 0x3FFF;
            return valid(w, h);
        }
        return Optional.empty();
    }

    private static Optional<Dimensions> probeJpeg(byte[] data) {
        int i = 2;
        while (i + 3 < data.length) {
            if ((data[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            while (i < data.length && (data[i] & 0xFF) == 0xFF) {
                i++;
            }
            if (i >= data.length) {
                break;
            }
            int marker = data[i++] & 0xFF;
            if (marker == 0xD9 || marker == 0xDA) { // EOI or SOS
                break;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                continue; // standalone markers
            }
            if (i + 1 >= data.length) {
                break;
            }
            int segLen = readUInt16BE(data, i);
            if (segLen < 2) {
                break;
            }
            if (isSofMarker(marker) && i + 6 < data.length) {
                int h = readUInt16BE(data, i + 3);
                int w = readUInt16BE(data, i + 5);
                return valid(w, h);
            }
            i += segLen;
        }
        return Optional.empty();
    }

    private static boolean isSofMarker(int marker) {
        return (marker >= 0xC0 && marker <= 0xC3)
                || (marker >= 0xC5 && marker <= 0xC7)
                || (marker >= 0xC9 && marker <= 0xCB)
                || (marker >= 0xCD && marker <= 0xCF);
    }

    private static String stripWhitespacePrefix(String raw, int limit) {
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), limit));
        for (int i = 0; i < raw.length() && sb.length() < limit; i++) {
            char c = raw.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Optional<Dimensions> valid(int width, int height) {
        return (width > 0 && height > 0 && width <= 65_536 && height <= 65_536)
                ? Optional.of(new Dimensions(width, height))
                : Optional.empty();
    }

    private static int readUInt16BE(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    private static int readUInt16LE(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int readUInt24LE(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16);
    }

    private static int readInt32BE(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24)
                | ((b[o + 1] & 0xFF) << 16)
                | ((b[o + 2] & 0xFF) << 8)
                | (b[o + 3] & 0xFF);
    }

    private static int readInt32LE(byte[] b, int o) {
        return (b[o] & 0xFF)
                | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16)
                | ((b[o + 3] & 0xFF) << 24);
    }
}

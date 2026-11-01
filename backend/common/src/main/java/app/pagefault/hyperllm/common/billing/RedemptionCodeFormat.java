package app.pagefault.hyperllm.common.billing;

import app.pagefault.hyperllm.common.crypto.HmacSha256;

import java.security.SecureRandom;
import java.util.Locale;

/** Presentation, normalization and digest of a redemption code. The plaintext is never stored. */
public final class RedemptionCodeFormat {

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private RedemptionCodeFormat() {
    }

    /** Uppercase alphanumerics only: dashes and spaces are presentation, not identity. */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    public static String hash(String normalized, String pepper) {
        return HmacSha256.hmacHex(normalized, pepper);
    }

    public static String generate() {
        StringBuilder code = new StringBuilder("HL-");
        for (int group = 0; group < 3; group++) {
            if (group > 0) {
                code.append('-');
            }
            for (int i = 0; i < 4; i++) {
                code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
            }
        }
        return code.toString();
    }

    public static String hint(String plaintext) {
        String normalized = normalize(plaintext);
        return normalized.length() <= 4 ? normalized : "****" + normalized.substring(normalized.length() - 4);
    }
}

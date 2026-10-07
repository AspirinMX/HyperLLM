package app.pagefault.hyperllm.common.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Cryptographic hashing helpers for HMAC-SHA256 API key digests and SHA-256 opaque token keys.
 */
public final class HmacSha256 {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final HexFormat LOWER_HEX = HexFormat.of();

    private HmacSha256() {
    }

    public static String hmacHex(String value, String pepper) {
        Objects.requireNonNull(value, "value must not be null");
        if (pepper == null || pepper.isEmpty()) {
            throw new IllegalArgumentException("HMAC pepper must not be empty");
        }
        return hmacHex(value.getBytes(StandardCharsets.UTF_8), pepper.getBytes(StandardCharsets.UTF_8));
    }

    public static String hmacHex(byte[] data, byte[] pepper) {
        Objects.requireNonNull(data, "data must not be null");
        if (pepper == null || pepper.length == 0) {
            throw new IllegalArgumentException("HMAC pepper must not be empty");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(pepper, HMAC_ALGORITHM));
            return LOWER_HEX.formatHex(mac.doFinal(data));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 computation failed", ex);
        }
    }

    public static String sha256Hex(String value) {
        Objects.requireNonNull(value, "value must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return LOWER_HEX.formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public static boolean constantTimeEqualsHex(String expectedHex, String actualHex) {
        if (expectedHex == null || actualHex == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.UTF_8),
                actualHex.getBytes(StandardCharsets.UTF_8)
        );
    }
}

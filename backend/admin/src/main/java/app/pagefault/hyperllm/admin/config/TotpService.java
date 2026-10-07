package app.pagefault.hyperllm.admin.config;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;

/**
 * Pure Java RFC 6238 HMAC-SHA1 TOTP secret generator and 6-digit verifier.
 */
public final class TotpService {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int[] BASE32_LOOKUP = new int[128];
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 20;
    private static final int PERIOD_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int ALLOWED_WINDOW_DRIFT = 1;

    static {
        Arrays.fill(BASE32_LOOKUP, -1);
        for (int i = 0; i < BASE32_ALPHABET.length(); i++) {
            char c = BASE32_ALPHABET.charAt(i);
            BASE32_LOOKUP[c] = i;
            BASE32_LOOKUP[Character.toLowerCase(c)] = i;
        }
    }

    private TotpService() {
    }

    public static String generateBase32Secret() {
        byte[] bytes = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    public static String buildOtpAuthUri(String issuer, String accountEmail, String base32Secret) {
        return "otpauth://totp/" + urlEncode(issuer) + ":" + urlEncode(accountEmail)
                + "?secret=" + base32Secret
                + "&issuer=" + urlEncode(issuer)
                + "&digits=" + DIGITS
                + "&period=" + PERIOD_SECONDS;
    }

    public static boolean verifyCode(String base32Secret, String submittedCode, Instant now) {
        if (base32Secret == null || submittedCode == null || !submittedCode.matches("^\\d{6}$")) {
            return false;
        }
        byte[] key = decodeBase32(base32Secret);
        long currentStep = now.getEpochSecond() / PERIOD_SECONDS;
        for (int offset = -ALLOWED_WINDOW_DRIFT; offset <= ALLOWED_WINDOW_DRIFT; offset++) {
            String candidate = generateCodeForStep(key, currentStep + offset);
            if (MessageDigest.isEqual(
                    candidate.getBytes(StandardCharsets.UTF_8),
                    submittedCode.getBytes(StandardCharsets.UTF_8)
            )) {
                return true;
            }
        }
        return false;
    }

    static String generateCode(String base32Secret, Instant now) {
        byte[] key = decodeBase32(base32Secret);
        return generateCodeForStep(key, now.getEpochSecond() / PERIOD_SECONDS);
    }

    static String generateCodeForStep(byte[] key, long step) {
        try {
            byte[] data = ByteBuffer.allocate(8).putLong(step).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % 1_000_000;
            return String.format("%06d", otp);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA1 unavailable for TOTP", ex);
        }
    }

    private static String encodeBase32(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return out.toString();
    }

    private static byte[] decodeBase32(String input) {
        String clean = input.trim().replace("=", "").replace(" ", "");
        byte[] out = new byte[(clean.length() * 5) / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int idx = 0;
        for (int i = 0; i < clean.length(); i++) {
            char ch = clean.charAt(i);
            if (ch >= BASE32_LOOKUP.length || BASE32_LOOKUP[ch] < 0) {
                throw new IllegalArgumentException("Invalid Base32 character in TOTP secret");
            }
            buffer = (buffer << 5) | BASE32_LOOKUP[ch];
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[idx++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

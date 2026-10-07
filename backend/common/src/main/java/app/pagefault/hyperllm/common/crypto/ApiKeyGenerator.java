package app.pagefault.hyperllm.common.crypto;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * Generates high-entropy Base62 client API keys ({@code sk-hl-...}) and Playground ephemeral
 * tokens ({@code sk-hl-sb-...}).
 */
public final class ApiKeyGenerator {

    public static final String LIVE_PREFIX = "sk-hl-";
    public static final String SANDBOX_PREFIX = "sk-hl-sb-";
    public static final int LIVE_VISIBLE_LENGTH = 8;
    public static final int LIVE_SECRET_LENGTH = 40;
    public static final int LIVE_TOTAL_LENGTH = LIVE_PREFIX.length() + LIVE_VISIBLE_LENGTH + LIVE_SECRET_LENGTH;
    public static final int SANDBOX_RANDOM_LENGTH = 32;
    public static final int SANDBOX_TOTAL_LENGTH = SANDBOX_PREFIX.length() + SANDBOX_RANDOM_LENGTH;

    private static final char[] BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private ApiKeyGenerator() {
    }

    public record GeneratedApiKey(String rawKey, String keyPrefix) {
    }

    public static GeneratedApiKey generateLiveKey() {
        String rawKey = LIVE_PREFIX + randomBase62(LIVE_VISIBLE_LENGTH + LIVE_SECRET_LENGTH);
        return new GeneratedApiKey(rawKey, maskLiveKey(rawKey));
    }

    public static String generateSandboxToken() {
        return SANDBOX_PREFIX + randomBase62(SANDBOX_RANDOM_LENGTH);
    }

    public static String maskLiveKey(String rawKey) {
        Objects.requireNonNull(rawKey, "rawKey must not be null");
        if (!isLiveKey(rawKey) || rawKey.length() != LIVE_TOTAL_LENGTH) {
            throw new IllegalArgumentException("Expected 54-char live key starting with " + LIVE_PREFIX);
        }
        int visibleEnd = LIVE_PREFIX.length() + LIVE_VISIBLE_LENGTH;
        return rawKey.substring(0, visibleEnd) + "..." + rawKey.substring(rawKey.length() - 4);
    }

    public static boolean isLiveKey(String token) {
        return token != null && token.startsWith(LIVE_PREFIX) && !token.startsWith(SANDBOX_PREFIX);
    }

    public static boolean isSandboxKey(String token) {
        return token != null && token.startsWith(SANDBOX_PREFIX);
    }

    private static String randomBase62(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = BASE62[SECURE_RANDOM.nextInt(BASE62.length)];
        }
        return new String(out);
    }
}

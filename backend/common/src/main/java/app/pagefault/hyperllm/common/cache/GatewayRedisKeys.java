package app.pagefault.hyperllm.common.cache;

import java.util.Locale;
import java.util.UUID;

/**
 * Canonical Redis L2 cache, OTP, sandbox, rate-limit, and wallet key builders shared across services.
 */
public final class GatewayRedisKeys {

    private GatewayRedisKeys() {
    }

    public static String otpCode(String purpose, String email) {
        return "gw:auth:otp:" + purpose.toLowerCase(Locale.ROOT) + ":" + email.trim().toLowerCase(Locale.ROOT);
    }

    public static String otpCooldown(String purpose, String email) {
        return "gw:auth:otp_cd:" + purpose.toLowerCase(Locale.ROOT) + ":" + email.trim().toLowerCase(Locale.ROOT);
    }

    public static String sandboxToken(String sha256Hex) {
        return "gw:sandbox:" + sha256Hex;
    }

    public static String keyConfig(String keyHash) {
        return "gw:cfg:key:" + keyHash;
    }

    public static String modelRoute(String modelCode) {
        return "gw:cfg:route:" + modelCode;
    }

    public static String wallet(UUID orgId) {
        return "gw:wallet:" + orgId;
    }

    public static String rpmCounter(String scope, UUID id, long epochMinute) {
        return "gw:rl:rpm:" + scope + ":" + id + ":" + epochMinute;
    }

    public static String concurrencyCounter(String scope, UUID id) {
        return "gw:rl:conc:" + scope + ":" + id;
    }
}

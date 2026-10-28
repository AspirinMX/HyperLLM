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

    public static String hold(String holdId) {
        return "gw:hold:" + holdId;
    }

    public static String holdDone(String holdId) {
        return "gw:hold:done:" + holdId;
    }

    public static String holdsExpiry() {
        return "gw:holds:exp";
    }

    public static String orgHolds(UUID orgId) {
        return "gw:org:holds:" + orgId;
    }

    /** Zset of the batches that may serve one model, scored by expiry. */
    public static String pack(UUID orgId, String modelCode) {
        return "gw:pack:" + orgId + ":" + modelCode;
    }

    /** Zset of the organisation's bonus batches, scored by expiry. */
    public static String bonus(UUID orgId) {
        return "gw:bonus:" + orgId;
    }

    public static String packAvailability(UUID orgId, String modelCode) {
        return "gw:pack:avail:" + orgId + ":" + modelCode;
    }

    public static String packHeld(UUID orgId, String modelCode) {
        return "gw:pack:held:" + orgId + ":" + modelCode;
    }

    public static String modelOverride(UUID orgId, UUID virtualModelId) {
        return "gw:cfg:override:" + orgId + ":" + virtualModelId;
    }

    public static String wallet(UUID orgId) {
        return "gw:wallet:" + orgId;
    }

    /** Prefix of the per-batch mirrors; a Lua script concatenates it with the batch id. */
    public static String packItemPrefix() {
        return "gw:pack:item:";
    }

    public static String bonusItemPrefix() {
        return "gw:bonus:item:";
    }

    public static String packItem(UUID packId) {
        return packItemPrefix() + packId;
    }

    public static String bonusItem(UUID grantId) {
        return bonusItemPrefix() + grantId;
    }

    /** Fold position of one organisation: the last event it produced and when it started lagging. */
    public static String ledgerPosition(UUID orgId) {
        return "gw:ledger:pos:" + orgId;
    }

    /** Copy of a settlement payload, written in the same script that moves the money. */
    public static String settleEvent(String eventId) {
        return "gw:settle:evt:" + eventId;
    }

    /** Identity of the live keys that were debited, kept beside the immutable copy. */
    public static String settleProof(String eventId) {
        return "gw:settle:proof:" + eventId;
    }

    /** Settlements awaiting the consumer's confirmation; the re-delivery job scans it. */
    public static String settlePending() {
        return "gw:settle:pending";
    }

    /** Organisation and occurrence time of each unconfirmed settlement, even if its copy expires. */
    public static String settlePendingOwners() {
        return "gw:settle:owners";
    }

    /** Published backlog signal read by every gateway instance. */
    public static String ledgerLagSignal() {
        return "gw:health:ledger-lag";
    }

    /** Marker proving the lag monitor ran at least once, so a missing signal can be distinguished. */
    public static String ledgerLagArmed() {
        return "gw:health:ledger-lag:armed";
    }

    /** Idempotency marker of an issuance; consumption reuses the hold's done marker instead. */
    public static String issuanceIdempotency(String eventId) {
        return "gw:wallet:idem:" + eventId;
    }

    public static String rpmCounter(String scope, UUID id, long epochMinute) {
        return "gw:rl:rpm:" + scope + ":" + id + ":" + epochMinute;
    }

    public static String concurrencyCounter(String scope, UUID id) {
        return "gw:rl:conc:" + scope + ":" + id;
    }
}

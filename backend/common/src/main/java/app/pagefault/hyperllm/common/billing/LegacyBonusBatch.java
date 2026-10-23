package app.pagefault.hyperllm.common.billing;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Identity of the bonus balance that predates batches.
 *
 * <p>A tenant created before batches existed has money in the wallet snapshot and no grant row
 * behind it. The mirror still has to make that balance spendable, so it is carried as one synthetic
 * batch whose id is derived from the organisation: every load produces the same id, and the consumer
 * can recognise it and skip the batch decrement that has no row to decrement.
 */
public final class LegacyBonusBatch {

    private static final UUID EPOCH = UUID.fromString("00000000-0000-0000-0000-000000000000");

    /** Expiry used for a balance that never expires. */
    public static final long NO_EXPIRY_EPOCH_MS = 4_102_444_800_000L;

    private LegacyBonusBatch() {
    }

    public static UUID idFor(UUID orgId) {
        return UUID.nameUUIDFromBytes(("legacy-bonus:" + orgId).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isLegacy(UUID orgId, UUID batchId) {
        return batchId != null && !EPOCH.equals(batchId) && batchId.equals(idFor(orgId));
    }
}

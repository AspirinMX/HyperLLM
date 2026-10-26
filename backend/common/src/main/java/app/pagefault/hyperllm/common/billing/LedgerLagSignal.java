package app.pagefault.hyperllm.common.billing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * How far the ledger consumer is behind, published by the operator console for every gateway.
 *
 * <p>It is deliberately a plain value: the gateway only asks one question of it - is delivery keeping
 * up well enough that rebuilding a tenant from the database would still be nearly current - and a
 * richer object would invite the gateway to reason about numbers it cannot verify.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LedgerLagSignal(
        boolean degraded,
        long lagRecords,
        double drainSeconds,
        long updatedAtEpochMs
) {

    public static LedgerLagSignal healthy(long lagRecords, double drainSeconds, long updatedAtEpochMs) {
        return new LedgerLagSignal(false, lagRecords, drainSeconds, updatedAtEpochMs);
    }

    public static LedgerLagSignal degraded(long lagRecords, double drainSeconds, long updatedAtEpochMs) {
        return new LedgerLagSignal(true, lagRecords, drainSeconds, updatedAtEpochMs);
    }

    /** Seconds a client should wait before retrying, bounded so the header stays useful. */
    public long retryAfterSeconds() {
        long seconds = (long) Math.ceil(Math.max(drainSeconds, 1d));
        return Math.min(Math.max(seconds, 1L), 300L);
    }
}

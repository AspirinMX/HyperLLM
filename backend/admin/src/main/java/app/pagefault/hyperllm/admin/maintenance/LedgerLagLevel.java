package app.pagefault.hyperllm.admin.maintenance;

/**
 * Decides whether the ledger consumer is far enough behind to matter.
 *
 * <p>Records alone are a poor measure: five thousand records at a thousand per second is five
 * seconds of lag, while the same count at ten per second is over eight minutes. The drain time is
 * what a rebuild would actually be stale by, so it is the primary judge and the record count exists
 * to catch the moment the consumer stops entirely.
 */
public final class LedgerLagLevel {

    private LedgerLagLevel() {
    }

    public static boolean isDegraded(
            long lagRecords,
            double drainSeconds,
            long recordsThreshold,
            double drainSecondsThreshold
    ) {
        return lagRecords > recordsThreshold || drainSeconds > drainSecondsThreshold;
    }

    /** Seconds needed to empty the backlog at the observed rate, floored so an idle consumer still ranks. */
    public static double drainSeconds(long lagRecords, double recordsPerSecond) {
        return lagRecords / Math.max(recordsPerSecond, 0.1d);
    }
}

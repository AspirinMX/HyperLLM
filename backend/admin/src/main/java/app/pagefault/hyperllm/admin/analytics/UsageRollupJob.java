package app.pagefault.hyperllm.admin.analytics;

import app.pagefault.hyperllm.admin.config.AdminProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Recomputes the dirty hours from the committed facts.
 *
 * <p>An hour is claimed by clearing its dirty flag first; events that arrive during the computation
 * set it again, so the next run rebuilds the hour and the published version is always complete.
 * The facts of a version are inserted before the version is published, which keeps a crash from
 * exposing a partial hour.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsageRollupJob {

    private final AnalyticsCursor cursor;
    private final ClickHouseAnalyticsStore store;
    private final AdminProperties properties;

    @Scheduled(fixedDelayString = "${hyperllm.analytics.rollup-delay:PT30S}")
    public void rollup() {
        if (!properties.getAnalytics().isEnabled()) {
            return;
        }
        rollupOnce();
    }

    /** Runs one pass; returns how many hours were published. Kept public for tests. */
    public int rollupOnce() {
        List<OffsetDateTime> hours = cursor.claimDirtyHours(properties.getAnalytics().getRollupHoursPerRun());
        if (hours.isEmpty()) {
            return 0;
        }
        try {
            store.ensureSchema();
            Map<Integer, Long> positions = cursor.positions();
            long version = cursor.nextVersion();
            int published = 0;
            for (OffsetDateTime hour : hours) {
                try {
                    store.rollupHour(hour, version, positions);
                    store.publishHour(hour, version);
                    cursor.published(hour, version);
                    published++;
                } catch (RuntimeException ex) {
                    cursor.markDirty(hour);
                    log.error("Cannot roll up usage hour {}", hour, ex);
                }
            }
            return published;
        } catch (RuntimeException ex) {
            hours.forEach(cursor::markDirty);
            throw ex;
        }
    }
}

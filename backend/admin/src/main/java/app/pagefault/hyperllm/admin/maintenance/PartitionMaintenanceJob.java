package app.pagefault.hyperllm.admin.maintenance;

import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Creates the monthly partitions before a row can arrive in a month that has none.
 *
 * <p>The ledger and the audit log are range partitioned by {@code created_at}; inserting into a month
 * without a partition is an error, so the failure only shows up at the worst moment. The migration
 * seeds a window ahead of itself, and this job keeps that window rolling. It reuses the same
 * {@code ensure_monthly_partition} function the migration calls, so there is one definition of what a
 * partition looks like.
 */
@Slf4j
@Component
public class PartitionMaintenanceJob {

    private static final List<String> PARTITIONED_TABLES = List.of("wallet_ledger", "audit_logs");

    private final DSLContext dsl;
    private final int monthsAhead;

    public PartitionMaintenanceJob(
            DSLContext dsl,
            @Value("${hyperllm.maintenance.partition-months-ahead:3}") int monthsAhead
    ) {
        this.dsl = dsl;
        this.monthsAhead = monthsAhead;
    }

    @Scheduled(cron = "${hyperllm.maintenance.partition-cron:0 15 3 * * *}")
    public void ensureFuturePartitions() {
        OffsetDateTime monthStart = OffsetDateTime.now(ZoneOffset.UTC)
                .withDayOfMonth(1)
                .truncatedTo(ChronoUnit.DAYS);
        int created = 0;
        for (int offset = 0; offset <= monthsAhead; offset++) {
            OffsetDateTime target = monthStart.plusMonths(offset);
            for (String table : PARTITIONED_TABLES) {
                // Explicit casts: the driver sends bind parameters as varchar, and the function is
                // declared over (text, timestamptz).
                dsl.execute("select ensure_monthly_partition(?::text, ?::timestamptz)", table, target);
                created++;
            }
        }
        log.debug("ensured {} monthly partitions up to {}", created, monthStart.plusMonths(monthsAhead));
    }
}

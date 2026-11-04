package app.pagefault.hyperllm.domain.model.billing;

import app.pagefault.hyperllm.domain.model.enums.BudgetMonthState;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The PostgreSQL fold of one project's confirmed spending in one UTC month.
 *
 * <p>It trails the Redis authority by the settlement pipeline's lag and is the evidence a cold
 * start uses: the gateway installs the Redis month with this value only because everything older
 * has been folded here.
 */
public record ProjectBudgetMonth(
        UUID projectId,
        LocalDate budgetMonth,
        long confirmedMicros,
        BudgetMonthState state,
        OffsetDateTime firstEnabledAt,
        long version,
        OffsetDateTime updatedAt
) {
}

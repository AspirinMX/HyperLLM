package app.pagefault.hyperllm.console.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class BudgetDtos {

    private BudgetDtos() {
    }

    /**
     * One project's current UTC month.
     *
     * @param source {@code LIVE} while the gateway's keys hold the month, {@code CONFIRMED} when the
     *               answer comes from the PostgreSQL fold and may trail the newest settlements
     * @param state  {@code CONFIRMED} or {@code UNVERIFIED}; the latter means a settlement in this
     *               month could not be priced and a hard-capped project is refusing traffic
     */
    public record ProjectBudgetDto(
            UUID projectId,
            String month,
            Long hardCapMicros,
            Long softCapMicros,
            long usedMicros,
            long heldMicros,
            Long availableMicros,
            String state,
            OffsetDateTime monthStart,
            String source,
            OffsetDateTime observedAt
    ) {
    }
}

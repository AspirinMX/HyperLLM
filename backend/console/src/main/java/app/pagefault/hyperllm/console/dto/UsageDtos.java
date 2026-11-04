package app.pagefault.hyperllm.console.dto;

import java.time.OffsetDateTime;
import java.util.List;

public final class UsageDtos {

    private UsageDtos() {
    }

    public record UsageRow(
            String dimension,
            long requests,
            long successes,
            long promptTokens,
            long cachedReadTokens,
            long completionTokens,
            long reasoningTokens,
            long costMicros,
            long cashMicros,
            long bonusMicros,
            long upstreamCostMicros,
            long packUnits,
            double ttftP50Ms,
            double ttftP90Ms,
            double ttftP99Ms,
            double durationP50Ms,
            double durationP90Ms,
            double durationP99Ms
    ) {
    }

    public record UsageTotals(
            long requests,
            long successes,
            long promptTokens,
            long cachedReadTokens,
            long completionTokens,
            long reasoningTokens,
            long costMicros,
            long cashMicros,
            long bonusMicros,
            long upstreamCostMicros,
            long packUnits
    ) {
    }

    /**
     * @param groupBy {@code PROJECT}, {@code API_KEY}, {@code MODEL}, {@code CHANNEL} or {@code HOUR}
     * @param rows    only the published version of each hour is included
     */
    public record UsageReport(
            OffsetDateTime from,
            OffsetDateTime to,
            String groupBy,
            List<UsageRow> rows,
            UsageTotals totals
    ) {
    }
}

package app.pagefault.hyperllm.admin.dto;

import java.time.OffsetDateTime;
import java.util.List;

public final class AdminAnalyticsDtos {

    private AdminAnalyticsDtos() {
    }

    public record MarginRow(
            String dimension,
            long requests,
            long cashMicros,
            long bonusMicros,
            long costMicros,
            long upstreamCostMicros,
            long contributionMicros,
            long unknownCostRequests
    ) {
    }

    public record PackSalesRow(
            String channel,
            String currency,
            long orders,
            long amountMinor,
            long creditMicros
    ) {
    }

    public record PackSalesReport(
            OffsetDateTime from,
            OffsetDateTime to,
            List<PackSalesRow> paid,
            List<PackSalesRow> refunded
    ) {
    }

    public record RejectRow(
            int partition,
            long offset,
            String requestId,
            String errorClass,
            String detail,
            OffsetDateTime createdAt
    ) {
    }
}

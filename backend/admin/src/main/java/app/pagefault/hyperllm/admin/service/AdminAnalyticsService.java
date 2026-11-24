package app.pagefault.hyperllm.admin.service;

import app.pagefault.hyperllm.admin.analytics.AnalyticsCursor;
import app.pagefault.hyperllm.admin.analytics.ClickHouseAnalyticsStore;
import app.pagefault.hyperllm.admin.dto.AdminAnalyticsDtos.MarginRow;
import app.pagefault.hyperllm.admin.dto.AdminAnalyticsDtos.PackSalesReport;
import app.pagefault.hyperllm.admin.dto.AdminAnalyticsDtos.PackSalesRow;
import app.pagefault.hyperllm.admin.dto.AdminAnalyticsDtos.RejectRow;
import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static app.pagefault.hyperllm.domain.jooq.Tables.PAYMENT_ORDERS;


/** Read models for the operator report: request margin and resource-pack sales. */
@Service
@RequiredArgsConstructor
public class AdminAnalyticsService {

    private static final Duration MAX_RANGE = Duration.ofDays(366);

    private final ClickHouseAnalyticsStore store;
    private final AnalyticsCursor cursor;
    private final DSLContext dsl;

    public List<MarginRow> margin(OffsetDateTime from, OffsetDateTime to, String groupBy) {
        validateRange(from, to);
        String dimension = groupBy == null ? "CHANNEL" : groupBy.toUpperCase(Locale.ROOT);
        if (!dimension.equals("CHANNEL") && !dimension.equals("MODEL") && !dimension.equals("ORG")) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST,
                    "groupBy must be CHANNEL, MODEL or ORG");
        }
        return store.queryMargin(from, to, dimension).stream()
                .map(AdminAnalyticsService::toMargin)
                .toList();
    }

    public PackSalesReport packSales(OffsetDateTime from, OffsetDateTime to) {
        validateRange(from, to);
        return new PackSalesReport(from, to, sales(from, to, "PAID"), sales(from, to, "REFUNDED"));
    }

    public List<RejectRow> rejects(int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        return cursor.recentRejects(bounded).stream()
                .map(row -> new RejectRow(
                        ((Number) row.get("partition")).intValue(),
                        ((Number) row.get("offset")).longValue(),
                        (String) row.get("requestId"),
                        (String) row.get("errorClass"),
                        (String) row.get("detail"),
                        (OffsetDateTime) row.get("createdAt")))
                .toList();
    }

    private List<PackSalesRow> sales(OffsetDateTime from, OffsetDateTime to, String status) {
        var orders = DSL.count().as("orders");
        var amountMinor = DSL.coalesce(DSL.sum(PAYMENT_ORDERS.PAY_AMOUNT_MINOR), BigDecimal.ZERO).as("amount_minor");
        var creditMicros = DSL.coalesce(DSL.sum(PAYMENT_ORDERS.CREDIT_MICROS), BigDecimal.ZERO).as("credit_micros");
        return dsl.select(
                        PAYMENT_ORDERS.PAYMENT_CHANNEL,
                        PAYMENT_ORDERS.PAY_CURRENCY,
                        orders,
                        amountMinor,
                        creditMicros)
                .from(PAYMENT_ORDERS)
                .where(PAYMENT_ORDERS.STATUS.eq(status)
                        .and(PAYMENT_ORDERS.PAID_AT.ge(from))
                        .and(PAYMENT_ORDERS.PAID_AT.lt(to)))
                .groupBy(PAYMENT_ORDERS.PAYMENT_CHANNEL, PAYMENT_ORDERS.PAY_CURRENCY)
                .orderBy(PAYMENT_ORDERS.PAYMENT_CHANNEL, PAYMENT_ORDERS.PAY_CURRENCY)
                .fetch(row -> new PackSalesRow(
                        row.get(PAYMENT_ORDERS.PAYMENT_CHANNEL),
                        row.get(PAYMENT_ORDERS.PAY_CURRENCY),
                        row.get(orders, Long.class),
                        row.get(amountMinor, Long.class),
                        row.get(creditMicros, Long.class)));
    }

    private static MarginRow toMargin(Map<String, Object> row) {
        return new MarginRow(
                String.valueOf(row.get("dimension")),
                number(row.get("requests")),
                number(row.get("cashMicros")),
                number(row.get("bonusMicros")),
                number(row.get("costMicros")),
                number(row.get("upstreamCostMicros")),
                number(row.get("contributionMicros")),
                number(row.get("unknownCostRequests")));
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static void validateRange(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "The time range is invalid");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "The time range is too wide");
        }
    }
}

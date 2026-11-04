package app.pagefault.hyperllm.admin.analytics;

import app.pagefault.hyperllm.common.messaging.LlmRequestEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * The only place in admin that speaks ClickHouse.
 *
 * <p>Facts are appended with their Kafka position. The hourly rollup reads a deduplicated view cut
 * at the committed positions, writes one version, and only then publishes that version; readers
 * select the row of the newest published version per hour, so an interrupted recomputation is never
 * visible and a redelivered fact still counts once.
 */
@Slf4j
@Component
public class ClickHouseAnalyticsStore {

    private static final String INSERT_FACT = """
            INSERT INTO llm_request_events (
                created_at, request_id, trace_id, org_id, project_id, api_key_id, user_id,
                virtual_model, provider, channel_id, upstream_model, inbound_protocol, is_stream,
                http_status, error_code, retry_count, is_sticky_hit, guardrail_action, is_debug_logged,
                prompt_tokens, cached_read_tokens, cached_write_tokens, completion_tokens, reasoning_tokens,
                cost_micros, cash_cost_micros, bonus_cost_micros, upstream_cost_micros, pack_deducted_units,
                ttft_ms, tpot_ms, duration_ms, client_ip_prefix, region,
                kafka_partition, kafka_offset, cost_known
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1)
            """;

    public record FactRow(LlmRequestEvent event, int partition, long offset) {
    }

    private final AnalyticsPool pool;
    private volatile boolean schemaReady;

    public ClickHouseAnalyticsStore(AnalyticsPool pool) {
        this.pool = pool;
    }

    /** Runs the idempotent schema so an existing Compose volume is upgraded without manual DDL. */
    public synchronized void ensureSchema() {
        if (schemaReady) {
            return;
        }
        String script;
        try (InputStream in = new ClassPathResource("db/clickhouse/analytics-schema.sql").getInputStream()) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read the ClickHouse schema", ex);
        }
        try (Connection connection = pool.getConnection()) {
            for (String statement : stripComments(script).split(";")) {
                String sql = statement.trim();
                if (!sql.isEmpty()) {
                    try (PreparedStatement ps = connection.prepareStatement(sql)) {
                        ps.execute();
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot apply the ClickHouse schema", ex);
        }
        schemaReady = true;
    }

    public void insertFacts(List<FactRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        try (Connection connection = pool.getConnection();
             PreparedStatement ps = connection.prepareStatement(INSERT_FACT)) {
            Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            for (FactRow row : rows) {
                LlmRequestEvent event = row.event();
                int i = 1;
                ps.setTimestamp(i++, Timestamp.from(event.createdAt().toInstant()), utc);
                ps.setString(i++, event.requestId());
                ps.setString(i++, safe(event.traceId()));
                ps.setObject(i++, event.orgId());
                ps.setObject(i++, event.projectId());
                ps.setObject(i++, event.apiKeyId());
                if (event.userId() == null) {
                    ps.setObject(i++, null);
                } else {
                    ps.setObject(i++, event.userId());
                }
                ps.setString(i++, safe(event.virtualModel()));
                ps.setString(i++, safe(event.provider()));
                ps.setObject(i++, event.channelId());
                ps.setString(i++, safe(event.upstreamModel()));
                ps.setString(i++, safe(event.inboundProtocol()));
                ps.setInt(i++, event.isStream());
                ps.setInt(i++, event.httpStatus());
                ps.setString(i++, safe(event.errorCode()));
                ps.setInt(i++, event.retryCount());
                ps.setInt(i++, event.isStickyHit());
                ps.setString(i++, safe(event.guardrailAction()));
                ps.setInt(i++, event.isDebugLogged());
                ps.setInt(i++, event.promptTokens());
                ps.setInt(i++, event.cachedReadTokens());
                ps.setInt(i++, event.cachedWriteTokens());
                ps.setInt(i++, event.completionTokens());
                ps.setInt(i++, event.reasoningTokens());
                ps.setLong(i++, event.costMicros());
                ps.setLong(i++, event.cashCostMicros());
                ps.setLong(i++, event.bonusCostMicros());
                ps.setLong(i++, event.upstreamCostMicros());
                ps.setLong(i++, event.packDeductedUnits());
                ps.setInt(i++, event.ttftMs());
                ps.setDouble(i++, event.tpotMs());
                ps.setInt(i++, event.durationMs());
                ps.setString(i++, safe(event.clientIpPrefix()));
                ps.setString(i++, safe(event.region()));
                ps.setInt(i++, row.partition());
                ps.setLong(i++, row.offset());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot insert request facts into ClickHouse", ex);
        }
    }

    /**
     * Recomputes one hour from facts at or below the committed positions. The inner query keeps the
     * first fact of each {@code (org_id, request_id)} ordered by delivery, which is what makes a
     * re-delivery on another offset count once even before ClickHouse merges anything.
     */
    public void rollupHour(OffsetDateTime hour, long version, Map<Integer, Long> positions) {
        StringBuilder bounds = new StringBuilder();
        if (positions.isEmpty()) {
            bounds.append("1");
        } else {
            bounds.append('(');
            boolean first = true;
            for (Map.Entry<Integer, Long> entry : positions.entrySet()) {
                if (!first) {
                    bounds.append(" OR ");
                }
                first = false;
                bounds.append("(kafka_partition = ").append(entry.getKey())
                        .append(" AND kafka_offset < ").append(entry.getValue()).append(')');
            }
            bounds.append(')');
        }
        String sql = """
                INSERT INTO usage_hourly
                SELECT toStartOfHour(created_at) AS hour_bucket, toUInt64(%d) AS version,
                    org_id, project_id, api_key_id, virtual_model, channel_id,
                    toUInt64(count()) AS request_count,
                    toUInt64(countIf(http_status >= 200 AND http_status < 300)) AS success_count,
                    sum(prompt_tokens), sum(cached_read_tokens), sum(cached_write_tokens),
                    sum(completion_tokens), sum(reasoning_tokens),
                    sum(cost_micros), sum(cash_cost_micros), sum(bonus_cost_micros),
                    sum(upstream_cost_micros), sum(pack_deducted_units),
                    toUInt64(countIf(cost_known = 0)) AS unknown_cost_requests,
                    quantilesTDigestState(0.5, 0.9, 0.99)(ttft_ms),
                    quantilesTDigestState(0.5, 0.9, 0.99)(duration_ms)
                FROM (
                    SELECT * FROM llm_request_events
                    WHERE created_at >= ? AND created_at < ? AND %s
                    ORDER BY kafka_partition, kafka_offset
                    LIMIT 1 BY org_id, request_id
                )
                GROUP BY org_id, project_id, api_key_id, virtual_model, channel_id, hour_bucket
                """.formatted(version, bounds);
        try (Connection connection = pool.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            ps.setTimestamp(1, Timestamp.from(hour.toInstant()), utc);
            ps.setTimestamp(2, Timestamp.from(hour.plusHours(1).toInstant()), utc);
            ps.execute();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot roll up hour " + hour, ex);
        }
    }

    public void publishHour(OffsetDateTime hour, long version) {
        try (Connection connection = pool.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO usage_hourly_published (hour_bucket, version) VALUES (?, ?)")) {
            ps.setTimestamp(1, Timestamp.from(hour.toInstant()),
                    Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            ps.setLong(2, version);
            ps.execute();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot publish hour " + hour, ex);
        }
    }

    public List<Map<String, Object>> queryMargin(OffsetDateTime from, OffsetDateTime to, String groupBy) {
        String dimension = switch (groupBy) {
            case "MODEL" -> "virtual_model";
            case "ORG" -> "toString(org_id)";
            default -> "toString(channel_id)";
        };
        String sql = """
                SELECT %s AS dimension,
                    sum(request_count) AS requests,
                    sum(sum_cash_cost_micros) AS cash_micros,
                    sum(sum_bonus_cost_micros) AS bonus_micros,
                    sum(sum_cost_micros) AS cost_micros,
                    sum(sum_upstream_cost_micros) AS upstream_micros,
                    sum(unknown_cost_requests) AS unknown_cost_requests
                FROM usage_hourly
                WHERE hour_bucket >= ? AND hour_bucket < ?
                  AND (hour_bucket, version) IN (
                      SELECT hour_bucket, max(version) FROM usage_hourly_published
                      WHERE hour_bucket >= ? AND hour_bucket < ? GROUP BY hour_bucket)
                GROUP BY dimension
                ORDER BY requests DESC
                LIMIT 500
                """.formatted(dimension);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = pool.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            ps.setTimestamp(1, Timestamp.from(from.toInstant()), utc);
            ps.setTimestamp(2, Timestamp.from(to.toInstant()), utc);
            ps.setTimestamp(3, Timestamp.from(from.toInstant()), utc);
            ps.setTimestamp(4, Timestamp.from(to.toInstant()), utc);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    long cash = rs.getLong("cash_micros");
                    long upstream = rs.getLong("upstream_micros");
                    row.put("dimension", rs.getString("dimension"));
                    row.put("requests", rs.getLong("requests"));
                    row.put("cashMicros", cash);
                    row.put("bonusMicros", rs.getLong("bonus_micros"));
                    row.put("costMicros", rs.getLong("cost_micros"));
                    row.put("upstreamCostMicros", upstream);
                    row.put("contributionMicros", cash - upstream);
                    row.put("unknownCostRequests", rs.getLong("unknown_cost_requests"));
                    rows.add(row);
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot query the margin report", ex);
        }
        return rows;
    }

    public boolean isReachable() {
        try (Connection connection = pool.getConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT 1")) {
            ps.executeQuery().close();
            return true;
        } catch (SQLException ex) {
            return false;
        }
    }

    private static String stripComments(String script) {
        StringBuilder out = new StringBuilder(script.length());
        for (String line : script.split("\\R")) {
            String trimmed = line.stripLeading();
            if (!trimmed.startsWith("--")) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

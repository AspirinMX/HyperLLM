package app.pagefault.hyperllm.console.service;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.console.dto.UsageDtos.UsageReport;
import app.pagefault.hyperllm.console.dto.UsageDtos.UsageRow;
import app.pagefault.hyperllm.console.dto.UsageDtos.UsageTotals;
import app.pagefault.hyperllm.domain.model.enums.OrgRole;
import app.pagefault.hyperllm.domain.model.tenant.OrgMember;
import app.pagefault.hyperllm.domain.repository.ProjectMemberRepository;
import app.pagefault.hyperllm.console.config.ConsoleAnalyticsPool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Organisation usage report over the published hourly rollup.
 *
 * <p>The query always resolves the caller's project scope on the server: a developer sees only the
 * projects they were granted, and the organisation and project filters are pushed into ClickHouse
 * rather than applied to a fetched page. Only published versions participate, so a recomputation in
 * flight cannot change a report between two reads.
 */
@Slf4j
@Service
public class ConsoleUsageService {

    private static final Duration MAX_RANGE = Duration.ofDays(90);

    private final ConsoleRbacService rbac;
    private final ProjectMemberRepository projectMemberRepository;
    private final ConsoleAnalyticsPool pool;

    public ConsoleUsageService(
            ConsoleRbacService rbac,
            ProjectMemberRepository projectMemberRepository,
            ConsoleAnalyticsPool pool
    ) {
        this.rbac = rbac;
        this.projectMemberRepository = projectMemberRepository;
        this.pool = pool;
    }

    public UsageReport query(UUID orgId, UUID userId, OffsetDateTime from, OffsetDateTime to,
                             UUID projectId, UUID apiKeyId, String model, String groupBy) {
        validateRange(from, to);
        String dimension = groupBy == null ? "PROJECT" : groupBy.toUpperCase(Locale.ROOT);
        String dimensionSql = switch (dimension) {
            case "PROJECT" -> "toString(project_id)";
            case "API_KEY" -> "toString(api_key_id)";
            case "MODEL" -> "virtual_model";
            case "CHANNEL" -> "toString(channel_id)";
            case "HOUR" -> "formatDateTime(hour_bucket, '%Y-%m-%dT%H:00:00Z', 'UTC')";
            default -> throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST,
                    "groupBy must be PROJECT, API_KEY, MODEL, CHANNEL or HOUR");
        };

        OrgMember member = rbac.requireOrgMembership(orgId, userId);
        List<UUID> developerScope = null;
        if (member.role() == OrgRole.DEVELOPER) {
            developerScope = projectMemberRepository.listProjectIdsByUser(orgId, userId);
            if (projectId != null && !developerScope.contains(projectId)) {
                throw new HyperLlmException(ErrorCode.AUTH_FORBIDDEN, "Developer does not have access to this project");
            }
            if (developerScope.isEmpty()) {
                return empty(from, to, dimension);
            }
        }
        if (projectId != null) {
            rbac.requireProjectAccess(projectId, userId, false);
        }

        StringBuilder where = new StringBuilder("""
                org_id = toUUID(?)
                  AND hour_bucket >= ? AND hour_bucket < ?
                  AND (hour_bucket, version) IN (
                      SELECT hour_bucket, max(version) FROM usage_hourly_published
                      WHERE hour_bucket >= ? AND hour_bucket < ? GROUP BY hour_bucket)
                """);
        List<Object> params = new ArrayList<>();
        params.add(orgId.toString());
        params.add(from);
        params.add(to);
        params.add(from);
        params.add(to);
        if (projectId != null) {
            where.append(" AND project_id = toUUID(?)");
            params.add(projectId.toString());
        } else if (developerScope != null) {
            where.append(" AND project_id IN (");
            for (int i = 0; i < developerScope.size(); i++) {
                where.append(i == 0 ? "?" : ", ?");
                params.add(developerScope.get(i).toString());
            }
            where.append(')');
        }
        if (apiKeyId != null) {
            where.append(" AND api_key_id = toUUID(?)");
            params.add(apiKeyId.toString());
        }
        if (model != null && !model.isBlank()) {
            where.append(" AND virtual_model = ?");
            params.add(model);
        }

        String sql = """
                SELECT %s AS dimension,
                    sum(request_count) AS requests,
                    sum(success_count) AS successes,
                    sum(sum_prompt_tokens) AS prompt_tokens,
                    sum(sum_cached_read_tokens) AS cached_read_tokens,
                    sum(sum_completion_tokens) AS completion_tokens,
                    sum(sum_reasoning_tokens) AS reasoning_tokens,
                    sum(sum_cost_micros) AS cost_micros,
                    sum(sum_cash_cost_micros) AS cash_micros,
                    sum(sum_bonus_cost_micros) AS bonus_micros,
                    sum(sum_upstream_cost_micros) AS upstream_micros,
                    sum(sum_pack_units) AS pack_units,
                    quantilesTDigestMerge(0.5, 0.9, 0.99)(ttft_state)[1] AS ttft_p50,
                    quantilesTDigestMerge(0.5, 0.9, 0.99)(ttft_state)[2] AS ttft_p90,
                    quantilesTDigestMerge(0.5, 0.9, 0.99)(ttft_state)[3] AS ttft_p99,
                    quantilesTDigestMerge(0.5, 0.9, 0.99)(duration_state)[1] AS duration_p50,
                    quantilesTDigestMerge(0.5, 0.9, 0.99)(duration_state)[2] AS duration_p90,
                    quantilesTDigestMerge(0.5, 0.9, 0.99)(duration_state)[3] AS duration_p99
                FROM usage_hourly
                WHERE %s
                GROUP BY dimension
                ORDER BY requests DESC
                LIMIT 500
                """.formatted(dimensionSql, where);
        List<UsageRow> rows = new ArrayList<>();
        try (Connection connection = pool.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new UsageRow(
                            rs.getString("dimension"),
                            rs.getLong("requests"), rs.getLong("successes"),
                            rs.getLong("prompt_tokens"), rs.getLong("cached_read_tokens"),
                            rs.getLong("completion_tokens"), rs.getLong("reasoning_tokens"),
                            rs.getLong("cost_micros"), rs.getLong("cash_micros"),
                            rs.getLong("bonus_micros"), rs.getLong("upstream_micros"),
                            rs.getLong("pack_units"),
                            rs.getDouble("ttft_p50"), rs.getDouble("ttft_p90"), rs.getDouble("ttft_p99"),
                            rs.getDouble("duration_p50"), rs.getDouble("duration_p90"),
                            rs.getDouble("duration_p99")));
                }
            }
        } catch (SQLException ex) {
            log.error("Usage query failed for org {}", orgId, ex);
            throw new HyperLlmException(ErrorCode.ANALYTICS_UNAVAILABLE, "The usage warehouse is unavailable");
        }
        return new UsageReport(from, to, dimension, rows, totals(rows));
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            if (value instanceof OffsetDateTime at) {
                ps.setTimestamp(i + 1, Timestamp.from(at.toInstant()), utc);
            } else {
                ps.setString(i + 1, String.valueOf(value));
            }
        }
    }

    private static UsageTotals totals(List<UsageRow> rows) {
        return new UsageTotals(
                rows.stream().mapToLong(UsageRow::requests).sum(),
                rows.stream().mapToLong(UsageRow::successes).sum(),
                rows.stream().mapToLong(UsageRow::promptTokens).sum(),
                rows.stream().mapToLong(UsageRow::cachedReadTokens).sum(),
                rows.stream().mapToLong(UsageRow::completionTokens).sum(),
                rows.stream().mapToLong(UsageRow::reasoningTokens).sum(),
                rows.stream().mapToLong(UsageRow::costMicros).sum(),
                rows.stream().mapToLong(UsageRow::cashMicros).sum(),
                rows.stream().mapToLong(UsageRow::bonusMicros).sum(),
                rows.stream().mapToLong(UsageRow::upstreamCostMicros).sum(),
                rows.stream().mapToLong(UsageRow::packUnits).sum());
    }

    private static UsageReport empty(OffsetDateTime from, OffsetDateTime to, String dimension) {
        return new UsageReport(from, to, dimension, List.of(), totals(List.of()));
    }

    private static void validateRange(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST, "The time range is invalid");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new HyperLlmException(ErrorCode.PROTOCOL_INVALID_REQUEST,
                    "The usage report covers at most 90 days");
        }
    }
}

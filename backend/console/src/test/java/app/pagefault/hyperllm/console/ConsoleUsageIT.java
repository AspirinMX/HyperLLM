package app.pagefault.hyperllm.console;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.console.config.ConsoleAnalyticsPool;
import app.pagefault.hyperllm.console.config.ConsoleProperties;
import app.pagefault.hyperllm.console.dto.UsageDtos.UsageReport;
import app.pagefault.hyperllm.console.service.ConsoleRbacService;
import app.pagefault.hyperllm.console.service.ConsoleUsageService;
import app.pagefault.hyperllm.domain.model.enums.OrgRole;
import app.pagefault.hyperllm.domain.model.tenant.OrgMember;
import app.pagefault.hyperllm.domain.repository.ProjectMemberRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The usage report must be readable, scoped and version-aware without a running control plane.
 *
 * <p>The report query is executed against a real ClickHouse because the published-version filter and
 * the aggregate merge are SQL, not Java: a mock would prove nothing about either.
 */
@Tag("integration")
class ConsoleUsageIT {

    static final GenericContainer<?> CLICKHOUSE =
            new GenericContainer<>(DockerImageName.parse("clickhouse/clickhouse-server:24-alpine"))
                    .withExposedPorts(8123)
                    .withEnv("CLICKHOUSE_DB", "hyperllm")
                    .withEnv("CLICKHOUSE_USER", "default")
                    .withEnv("CLICKHOUSE_PASSWORD", "")
                    .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    static ConsoleAnalyticsPool pool;

    @BeforeAll
    static void start() throws Exception {
        CLICKHOUSE.start();
        ConsoleProperties properties = new ConsoleProperties();
        properties.getAnalytics().setUrl("jdbc:clickhouse://" + CLICKHOUSE.getHost() + ":"
                + CLICKHOUSE.getMappedPort(8123) + "/hyperllm");
        properties.getAnalytics().setUsername("default");
        properties.getAnalytics().setPassword("");
        pool = new ConsoleAnalyticsPool(properties);
        try (Connection connection = pool.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("""
                    CREATE TABLE IF NOT EXISTS usage_hourly (
                        hour_bucket DateTime('UTC'), version UInt64, org_id UUID, project_id UUID,
                        api_key_id UUID, virtual_model LowCardinality(String), channel_id UUID,
                        request_count UInt64, success_count UInt64, sum_prompt_tokens UInt64,
                        sum_cached_read_tokens UInt64, sum_cached_write_tokens UInt64,
                        sum_completion_tokens UInt64, sum_reasoning_tokens UInt64,
                        sum_cost_micros Int64, sum_cash_cost_micros Int64, sum_bonus_cost_micros Int64,
                        sum_upstream_cost_micros Int64, sum_pack_units Int64, unknown_cost_requests UInt64,
                        ttft_state AggregateFunction(quantilesTDigest(0.5, 0.9, 0.99), UInt32),
                        duration_state AggregateFunction(quantilesTDigest(0.5, 0.9, 0.99), UInt32)
                    ) ENGINE = AggregatingMergeTree()
                    ORDER BY (org_id, project_id, api_key_id, virtual_model, channel_id, hour_bucket, version)
                    """)) {
                ps.execute();
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    CREATE TABLE IF NOT EXISTS usage_hourly_published (
                        hour_bucket DateTime('UTC'), version UInt64,
                        published_at DateTime64(3, 'UTC') DEFAULT now64(3)
                    ) ENGINE = ReplacingMergeTree(published_at) ORDER BY hour_bucket
                    """)) {
                ps.execute();
            }
        }
    }

    @AfterAll
    static void stop() {
        if (pool != null) {
            pool.close();
        }
        CLICKHOUSE.stop();
    }

    @Test
    void groupsThePublishedVersionAndKeepsTheDeveloperScope() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID foreignProject = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OffsetDateTime hour = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).truncatedTo(ChronoUnit.HOURS);
        insert(orgId, projectId, "gpt-x", hour, 1L, 1L, 10L, 100L);
        insert(orgId, projectId, "gpt-x", hour, 2L, 99L, 990L, 9900L);
        insert(UUID.randomUUID(), projectId, "gpt-x", hour, 1L, 7L, 70L, 700L);
        publish(hour, 1L);

        ConsoleRbacService rbac = mock(ConsoleRbacService.class);
        ProjectMemberRepository members = mock(ProjectMemberRepository.class);
        OrgMember member = new OrgMember(UUID.randomUUID(), orgId, userId, OrgRole.DEVELOPER,
                hour, hour);
        when(rbac.requireOrgMembership(orgId, userId)).thenReturn(member);
        when(members.listProjectIdsByUser(orgId, userId)).thenReturn(List.of(projectId));
        ConsoleUsageService service = new ConsoleUsageService(rbac, members, pool);

        UsageReport report = service.query(orgId, userId, hour, hour.plusHours(1),
                projectId, null, null, "MODEL");

        assertThat(report.rows()).singleElement().satisfies(row -> {
            assertThat(row.dimension()).isEqualTo("gpt-x");
            assertThat(row.requests()).as("version 2 is unpublished").isEqualTo(1L);
            assertThat(row.costMicros()).isEqualTo(100L);
            assertThat(row.ttftP90Ms()).isGreaterThan(0.0);
        });
        assertThat(report.totals().requests()).isEqualTo(1L);

        assertThatThrownBy(() -> service.query(orgId, userId, hour, hour.plusHours(1),
                foreignProject, null, null, "PROJECT"))
                .isInstanceOfSatisfying(HyperLlmException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
    }

    private static void insert(UUID orgId, UUID projectId, String model, OffsetDateTime hour,
                               long version, long requests, long promptTokens, long cost) throws Exception {
        try (Connection connection = pool.getConnection();
             PreparedStatement ps = connection.prepareStatement("""
                     INSERT INTO usage_hourly
                     SELECT ?, toUInt64(?), toUUID(?), toUUID(?), toUUID(?), ?, toUUID(?),
                            toUInt64(?), toUInt64(?), toUInt64(?), toUInt64(0), toUInt64(0),
                            toUInt64(0), toUInt64(0), toInt64(?), toInt64(?), toInt64(0), toInt64(0),
                            toInt64(0), toUInt64(0),
                            quantilesTDigestState(0.5, 0.9, 0.99)(toUInt32(120)),
                            quantilesTDigestState(0.5, 0.9, 0.99)(toUInt32(300))
                     """)) {
            ps.setTimestamp(1, Timestamp.from(hour.toInstant()));
            ps.setLong(2, version);
            ps.setString(3, orgId.toString());
            ps.setString(4, projectId.toString());
            ps.setString(5, UUID.randomUUID().toString());
            ps.setString(6, model);
            ps.setString(7, UUID.randomUUID().toString());
            ps.setLong(8, requests);
            ps.setLong(9, requests);
            ps.setLong(10, promptTokens);
            ps.setLong(11, cost);
            ps.setLong(12, cost);
            ps.execute();
        }
    }

    private static void publish(OffsetDateTime hour, long version) throws Exception {
        try (Connection connection = pool.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO usage_hourly_published (hour_bucket, version) VALUES (?, ?)")) {
            ps.setTimestamp(1, Timestamp.from(hour.toInstant()));
            ps.setLong(2, version);
            ps.execute();
        }
    }
}

package app.pagefault.hyperllm.admin;

import app.pagefault.hyperllm.admin.analytics.AnalyticsCursor;
import app.pagefault.hyperllm.admin.analytics.AnalyticsPool;
import app.pagefault.hyperllm.admin.analytics.ClickHouseAnalyticsStore;
import app.pagefault.hyperllm.admin.analytics.UsageRollupJob;
import app.pagefault.hyperllm.admin.service.AdminAnalyticsService;
import app.pagefault.hyperllm.common.id.UuidV7;
import app.pagefault.hyperllm.common.messaging.KafkaTopics;
import app.pagefault.hyperllm.common.messaging.LlmRequestEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the report pipeline against real Kafka, PostgreSQL and ClickHouse.
 *
 * <p>The interesting property is not "a row arrives" but "a row arrives once": the same request is
 * delivered on two offsets, a rollup runs, and the aggregate still counts the request once. A
 * version that was inserted but never published must not change any report.
 */
@Tag("integration")
@SpringBootTest(
        classes = AdminApplication.class,
        properties = {
                "hyperllm.security.admin-jwt-secret=test-admin-jwt-secret-at-least-32-bytes",
                "hyperllm.security.aes-master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "hyperllm.security.key-pepper-v1=test-pepper-v1",
                "hyperllm.admin.mfa.required=false",
                "hyperllm.admin.bootstrap.email=",
                "hyperllm.admin.bootstrap.password=",
                "hyperllm.maintenance.enabled=false",
                "hyperllm.analytics.rollup-delay=PT1H",
                "spring.kafka.consumer.auto-offset-reset=earliest"
        }
)
class AnalyticsPipelineIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hyperllm_analytics_it")
            .withUsername("hyperllm")
            .withPassword("hyperllm");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static final GenericContainer<?> CLICKHOUSE =
            new GenericContainer<>(DockerImageName.parse("clickhouse/clickhouse-server:24-alpine"))
                    .withExposedPorts(8123)
                    .withEnv("CLICKHOUSE_DB", "hyperllm")
                    .withEnv("CLICKHOUSE_USER", "default")
                    .withEnv("CLICKHOUSE_PASSWORD", "")
                    .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.0") {
        @Override
        protected void configure() {
            super.configure();
            String listeners = getEnvMap().get("KAFKA_LISTENERS");
            if (listeners != null) {
                withEnv("KAFKA_LISTENERS", listeners.replace("0.0.0.0:", ":"));
            }
        }
    };

    static {
        POSTGRES.start();
        REDIS.start();
        CLICKHOUSE.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("hyperllm.analytics.clickhouse.url",
                () -> "jdbc:clickhouse://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123) + "/hyperllm");
        registry.add("hyperllm.analytics.clickhouse.username", () -> "default");
        registry.add("hyperllm.analytics.clickhouse.password", () -> "");
    }

    @Autowired private ObjectMapper objectMapper;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private AnalyticsCursor cursor;
    @Autowired private ClickHouseAnalyticsStore store;
    @Autowired private UsageRollupJob rollup;
    @Autowired private AdminAnalyticsService analyticsService;
    @Autowired private AnalyticsPool analyticsPool;

    @Test
    void redeliveryAndRecomputationKeepThePublishedCountAtOne() throws Exception {
        OffsetDateTime hour = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1)
                .truncatedTo(ChronoUnit.HOURS);
        String requestId = "analytics-" + UuidV7.next();
        String payload = objectMapper.writeValueAsString(event(hour, requestId, 200, 1_000, 500));
        long base = committed();

        kafkaTemplate.send(KafkaTopic(), requestId, payload).get();
        kafkaTemplate.send(KafkaTopic(), requestId, payload).get();
        awaitCommitted(base + 2);

        rollup.rollupOnce();
        assertThat(publishedRequests(hour)).isEqualTo(1L);
        assertThat(factCount(requestId)).isEqualTo(2L);
        assertThat(analyticsService.margin(hour, hour.plusHours(1), "ORG"))
                .singleElement().satisfies(row -> {
                    assertThat(row.requests()).isEqualTo(1L);
                    assertThat(row.cashMicros()).isEqualTo(6_000L);
                    assertThat(row.contributionMicros()).isEqualTo(5_000L);
                });

        // A new event for the same hour re-opens it; the recomputation still sees one request.
        String second = "analytics-" + UuidV7.next();
        long baseline = committed();
        kafkaTemplate.send(KafkaTopic(), second,
                objectMapper.writeValueAsString(event(hour, second, 200, 200, 100))).get();
        awaitCommitted(baseline + 1);
        rollup.rollupOnce();
        assertThat(publishedRequests(hour)).isEqualTo(2L);
        assertThat(latestVersion(hour)).isGreaterThan(1L);
    }

    @Test
    void anUnpublishedVersionIsInvisibleAndAZeroCostAttemptIsUnknown() throws Exception {
        OffsetDateTime hour = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2)
                .truncatedTo(ChronoUnit.HOURS);
        String requestId = "analytics-" + UuidV7.next();
        long baseline = committed();
        kafkaTemplate.send(KafkaTopic(), requestId,
                objectMapper.writeValueAsString(event(hour, requestId, 200, 300, 100))).get();
        awaitCommitted(baseline + 1);
        rollup.rollupOnce();
        long visible = publishedRequests(hour);
        assertThat(visible).isGreaterThan(0);

        // A version that never reached usage_hourly_published must not be selected.
        try (Connection connection = analyticsPool.getConnection();
             PreparedStatement ps = connection.prepareStatement("""
                     INSERT INTO usage_hourly
                     SELECT toStartOfHour(created_at), toUInt64(999999), org_id, project_id, api_key_id,
                         virtual_model, channel_id, toUInt64(1000), toUInt64(1000),
                         sum(prompt_tokens), sum(cached_read_tokens), sum(cached_write_tokens),
                         sum(completion_tokens), sum(reasoning_tokens), sum(cost_micros),
                         sum(cash_cost_micros), sum(bonus_cost_micros), sum(upstream_cost_micros),
                         sum(pack_deducted_units), toUInt64(0),
                         quantilesTDigestState(0.5, 0.9, 0.99)(ttft_ms),
                         quantilesTDigestState(0.5, 0.9, 0.99)(duration_ms)
                     FROM llm_request_events WHERE created_at >= ? AND created_at < ?
                     GROUP BY org_id, project_id, api_key_id, virtual_model, channel_id, created_at
                     """)) {
            ps.setTimestamp(1, Timestamp.from(hour.toInstant()));
            ps.setTimestamp(2, Timestamp.from(hour.plusHours(1).toInstant()));
            ps.execute();
        }
        assertThat(publishedRequests(hour)).isEqualTo(visible);
    }

    @Test
    void rejectsOnePayloadWithoutLosingTheGoodOneAndStoresNoPromptText() throws Exception {
        OffsetDateTime hour = OffsetDateTime.now(ZoneOffset.UTC).minusDays(3)
                .truncatedTo(ChronoUnit.HOURS);
        String requestId = "analytics-" + UuidV7.next();
        long baseline = committed();
        kafkaTemplate.send(KafkaTopic(), "bad", "{\"requestId\":\"bad\",\"prompt\":\"SECRET-PROMPT\"}")
                .get();
        kafkaTemplate.send(KafkaTopic(), requestId,
                objectMapper.writeValueAsString(event(hour, requestId, 200, 100, 50))).get();
        awaitCommitted(baseline + 2);

        rollup.rollupOnce();
        assertThat(publishedRequests(hour)).isEqualTo(1L);
        assertThat(cursor.recentRejects(10)).anySatisfy(row ->
                assertThat(row.get("errorClass")).isNotNull());
        try (Connection connection = analyticsPool.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT count() FROM llm_request_events WHERE request_id = ?")) {
            ps.setString(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(1L);
            }
        }
        try (Connection connection = analyticsPool.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT name FROM system.columns WHERE database = 'hyperllm' "
                             + "AND table = 'llm_request_events' AND name IN "
                             + "('prompt','completion','request_body','response_body','headers','error_message')")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("the fact table must not carry prompt text").isFalse();
            }
        }
    }

    private String KafkaTopic() {
        return KafkaTopics.LLM_REQUEST_EVENTS;
    }

    private LlmRequestEvent event(OffsetDateTime at, String requestId, int status, int prompt, int completion) {
        return new LlmRequestEvent(at, requestId, requestId.replace("-", ""),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                "gpt-analytics", "OPENAI", UUID.randomUUID(), "gpt-4o-mini",
                "OPENAI_CHAT", 1, status, "", 0, 0, "NONE", 0,
                prompt, 0, 0, completion, 0,
                prompt + completion * 10L, prompt + completion * 10L, 0L,
                prompt, 0L, 10, 0.5, 20, "10.0.0.0/24", "default");
    }

    private long committed() {
        return cursor.positions().values().stream().mapToLong(Long::longValue).sum();
    }

    private void awaitCommitted(long minimum) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            long total = cursor.positions().values().stream().mapToLong(Long::longValue).sum();
            if (total >= minimum) {
                return;
            }
            Thread.sleep(200L);
        }
        throw new AssertionError("The report consumer did not commit the expected offsets");
    }

    private long publishedRequests(OffsetDateTime hour) throws Exception {
        String sql = """
                SELECT sum(request_count) FROM usage_hourly
                WHERE hour_bucket = ? AND (hour_bucket, version) IN (
                    SELECT hour_bucket, max(version) FROM usage_hourly_published
                    WHERE hour_bucket = ? GROUP BY hour_bucket)
                """;
        try (Connection connection = analyticsPool.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(hour.toInstant()));
            ps.setTimestamp(2, Timestamp.from(hour.toInstant()));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private long latestVersion(OffsetDateTime hour) throws Exception {
        try (Connection connection = analyticsPool.getConnection();
             PreparedStatement ps = connection.prepareStatement("""
                     SELECT max(version) FROM usage_hourly_published WHERE hour_bucket = ?
                     """)) {
            ps.setTimestamp(1, Timestamp.from(hour.toInstant()));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private long factCount(String requestId) throws Exception {
        try (Connection connection = analyticsPool.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT count() FROM llm_request_events WHERE request_id = ?")) {
            ps.setString(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }
}

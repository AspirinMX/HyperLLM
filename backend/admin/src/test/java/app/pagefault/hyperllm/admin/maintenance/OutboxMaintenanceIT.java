package app.pagefault.hyperllm.admin.maintenance;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.domain.model.billing.OutboxEvent;
import app.pagefault.hyperllm.domain.repository.OutboxEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Both housekeeping jobs, against the stores they actually talk to. */
@Tag("integration")
class OutboxMaintenanceIT {

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private FakeRepository repository;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        repository = new FakeRepository();
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    @Test
    void purgesUpToTheWatermarkInBatches() {
        UUID watermark = UUID.randomUUID();
        repository.watermark = Optional.of(watermark);
        repository.updatedAt = Optional.of(OffsetDateTime.now(ZoneOffset.UTC));
        repository.remainingRows = 12;
        OutboxPurgeJob job = new OutboxPurgeJob(repository, 5, 24);

        job.purge();

        assertThat(repository.deletedUpTo).containsExactly(watermark, watermark, watermark);
        assertThat(repository.remainingRows).isZero();
    }

    @Test
    void removesByAgeOnceTheWatermarkStopsMoving() {
        repository.watermark = Optional.of(UUID.randomUUID());
        repository.updatedAt = Optional.of(OffsetDateTime.now(ZoneOffset.UTC).minusHours(48));
        OutboxPurgeJob job = new OutboxPurgeJob(repository, 5, 24);

        job.purge();

        assertThat(repository.deletedByAge).isTrue();
    }

    @Test
    void keepsRowsWhileTheWatermarkIsFresh() {
        repository.watermark = Optional.of(UUID.randomUUID());
        repository.updatedAt = Optional.of(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        repository.remainingRows = 0;
        OutboxPurgeJob job = new OutboxPurgeJob(repository, 5, 24);

        job.purge();

        assertThat(repository.deletedByAge).isFalse();
    }

    @Test
    void confirmationClearsEvidenceAndCannotBeUndoneByRescheduling() {
        String id = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        redis.opsForValue().set(GatewayRedisKeys.settleEvent(id), "{}");
        redis.opsForValue().set(GatewayRedisKeys.settleProof(id), "{}");
        redis.opsForHash().put(GatewayRedisKeys.settlePendingOwners(), id, UUID.randomUUID() + "|" + now);
        redis.opsForZSet().add(GatewayRedisKeys.settlePending(), id, now.toInstant().toEpochMilli());
        var worklist = new SettlementWorklist(redis);

        worklist.resolve(id);
        worklist.defer(id, now.plusMinutes(5));

        assertThat(redis.opsForZSet().score(GatewayRedisKeys.settlePending(), id)).isNull();
        assertThat(worklist.owner(id)).isNull();
        assertThat(worklist.payload(id)).isNull();
        assertThat(redis.hasKey(GatewayRedisKeys.settleProof(id))).isFalse();
    }

    private static final class FakeRepository implements OutboxEventRepository {

        private final List<UUID> deletedUpTo = new ArrayList<>();
        private Optional<UUID> watermark = Optional.empty();
        private Optional<OffsetDateTime> updatedAt = Optional.empty();
        private boolean deletedByAge;
        private int remainingRows;

        @Override
        public boolean insertIfAbsent(OutboxEvent event) {
            return true;
        }

        @Override
        public int insertIfAbsent(List<OutboxEvent> events) {
            return events.size();
        }

        @Override
        public boolean exists(UUID eventId) {
            return false;
        }

        @Override
        public Optional<UUID> watermark() {
            return watermark;
        }

        @Override
        public Optional<OffsetDateTime> watermarkUpdatedAt() {
            return updatedAt;
        }

        @Override
        public void advanceWatermark(UUID eventId) {
        }

        @Override
        public List<OutboxEvent> listUnconfirmedCredits(OffsetDateTime cutoff, int limit) {
            return List.of();
        }

        @Override
        public int deleteUpTo(UUID watermark, int limit) {
            deletedUpTo.add(watermark);
            int removed = Math.min(remainingRows, limit);
            remainingRows -= removed;
            return removed;
        }

        @Override
        public int deleteOlderThan(OffsetDateTime cutoff) {
            deletedByAge = true;
            return 1;
        }
    }
}

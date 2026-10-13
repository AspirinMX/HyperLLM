package app.pagefault.hyperllm.gateway;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.gateway.billing.PreHoldService;
import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the reservation lifecycle against a real Redis so the Lua atomicity is exercised, not mocked.
 * Expiry is simulated by rewriting the sorted-set score instead of sleeping.
 */
@Tag("integration")
class PreHoldServiceIT {

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    private LettuceConnectionFactory factory;
    private ReactiveStringRedisTemplate redis;
    private PreHoldService service;
    private GatewayProperties properties;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(factory);
        RedisConnections.flush(redis);

        properties = new GatewayProperties();
        service = new PreHoldService(
                redis,
                properties,
                admitScript(),
                renewScript(),
                settleScript(),
                releaseScript(),
                reclaimScript()
        );
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    @Test
    void admissionReservesEveryTierInItsOwnUnit() {
        UUID orgId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        seedWallet(orgId, 10_000L, 5_000L);
        setPackAvailability(orgId, "gpt", 1_000L);

        assertThat(admit(orgId, projectId, "gpt", 3_000L, 1_000L, 400L)).isEqualTo("OK");

        assertThat(wallet(orgId, "held_cash_micros")).isEqualTo(3_000L);
        assertThat(wallet(orgId, "held_bonus_micros")).isEqualTo(1_000L);
        assertThat(stringValue(GatewayRedisKeys.packHeld(orgId, "gpt"))).isEqualTo(400L);
    }

    @Test
    void aSecondHoldCannotSpendWhatTheFirstReserved() {
        UUID orgId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        seedWallet(orgId, 10_000L, 0L);
        setPackAvailability(orgId, "gpt", 0L);

        assertThat(admit(orgId, projectId, "gpt", 8_000L, 0L, 0L)).isEqualTo("OK");
        assertThat(admit(orgId, projectId, "gpt", 3_000L, 0L, 0L)).isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    void packUnitsAreReservedSeparately() {
        UUID orgId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        seedWallet(orgId, 10_000L, 0L);
        setPackAvailability(orgId, "gpt", 500L);

        assertThat(admit(orgId, projectId, "gpt", 1_000L, 0L, 400L)).isEqualTo("OK");
        assertThat(admit(orgId, projectId, "gpt", 1_000L, 0L, 200L)).isEqualTo("INSUFFICIENT_ENTITLEMENT");
    }

    @Test
    void settlementChargesExactlyOnceAndClampsAtZero() {
        UUID orgId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        seedWallet(orgId, 1_000L, 200L);
        setPackAvailability(orgId, "gpt", 0L);
        String holdId = admitHold(orgId, projectId, "gpt", 1_000L, 200L, 0L);

        PreHoldService.SettleOutcome first = service.settle(holdId, orgId, "gpt", 900L, 500L, 0L).block();
        assertThat(first).isNotNull();
        assertThat(first.cashCharged()).isEqualTo(900L);
        assertThat(first.bonusCharged()).as("bonus cannot exceed the 200 available").isEqualTo(200L);
        assertThat(wallet(orgId, "cash_micros")).isEqualTo(100L);
        assertThat(wallet(orgId, "general_bonus_micros")).isZero();
        assertThat(wallet(orgId, "held_cash_micros")).isZero();
        assertThat(wallet(orgId, "held_bonus_micros")).isZero();

        PreHoldService.SettleOutcome replay = service.settle(holdId, orgId, "gpt", 900L, 500L, 0L).block();
        assertThat(replay).isNotNull();
        assertThat(replay.alreadySettled()).isTrue();
        assertThat(wallet(orgId, "cash_micros")).as("a replayed settlement must not charge twice").isEqualTo(100L);
    }

    @Test
    void releasingReturnsTheReservationWithoutCharging() {
        UUID orgId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        seedWallet(orgId, 5_000L, 1_000L);
        setPackAvailability(orgId, "gpt", 0L);
        String holdId = admitHold(orgId, projectId, "gpt", 2_000L, 500L, 0L);

        assertThat(service.release(holdId, orgId).block()).isTrue();
        assertThat(wallet(orgId, "cash_micros")).isEqualTo(5_000L);
        assertThat(wallet(orgId, "general_bonus_micros")).isEqualTo(1_000L);
        assertThat(wallet(orgId, "held_cash_micros")).isZero();
        assertThat(wallet(orgId, "held_bonus_micros")).isZero();

        PreHoldService.SettleOutcome afterRelease = service.settle(holdId, orgId, "gpt", 2_000L, 500L, 0L).block();
        assertThat(afterRelease).isNotNull();
        assertThat(afterRelease.alreadySettled()).isTrue();
        assertThat(wallet(orgId, "cash_micros")).isEqualTo(5_000L);
    }

    @Test
    void reclaimReleasesOnlyExpiredLeasesAndOnlyOnce() {
        UUID orgId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        seedWallet(orgId, 5_000L, 0L);
        setPackAvailability(orgId, "gpt", 0L);
        String holdId = admitHold(orgId, projectId, "gpt", 2_000L, 0L, 0L);

        assertThat(service.reclaimExpired(orgId).block()).as("a live lease is untouched").isZero();

        expireNow(holdId);
        assertThat(service.reclaimExpired(orgId).block()).isEqualTo(1);
        assertThat(wallet(orgId, "held_cash_micros")).isZero();
        assertThat(service.reclaimExpired(orgId).block()).as("a reclaimed hold is gone").isZero();
    }

    @Test
    void aSettlementThatArrivesAfterTheReclaimStillCharges() {
        UUID orgId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        seedWallet(orgId, 5_000L, 0L);
        setPackAvailability(orgId, "gpt", 0L);
        String holdId = admitHold(orgId, projectId, "gpt", 2_000L, 0L, 0L);

        expireNow(holdId);
        assertThat(service.reclaimExpired(orgId).block()).isEqualTo(1);

        PreHoldService.SettleOutcome outcome = service.settle(holdId, orgId, "gpt", 1_500L, 0L, 0L).block();
        assertThat(outcome).isNotNull();
        assertThat(outcome.settledAfterReclaim()).isTrue();
        assertThat(outcome.cashCharged()).isEqualTo(1_500L);
        assertThat(wallet(orgId, "cash_micros")).isEqualTo(3_500L);
    }

    @Test
    void heartbeatKeepsTheLeaseFromBeingReclaimed() {
        UUID orgId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        seedWallet(orgId, 5_000L, 0L);
        setPackAvailability(orgId, "gpt", 0L);
        String holdId = admitHold(orgId, projectId, "gpt", 1_000L, 0L, 0L);

        expireNow(holdId);
        assertThat(service.renew(holdId, orgId, projectId, null, 300L).block()).isTrue();
        assertThat(service.reclaimExpired(orgId).block()).isZero();
        assertThat(wallet(orgId, "held_cash_micros")).isEqualTo(1_000L);
    }

    @Test
    void concurrentAdmissionsCannotExceedCapacity() {
        UUID orgId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        seedWallet(orgId, 40_000L, 0L);
        setPackAvailability(orgId, "gpt", 0L);

        long accepted = IntStream.range(0, 50)
                .parallel()
                .mapToObj(i -> admit(orgId, projectId, "gpt", 1_000L, 0L, 0L))
                .filter("OK"::equals)
                .count();

        assertThat(accepted).as("only 40 holds of 1_000 micros fit into 40_000").isEqualTo(40);
        assertThat(wallet(orgId, "held_cash_micros")).isEqualTo(40_000L);
        assertThat(wallet(orgId, "cash_micros")).isEqualTo(40_000L);
    }

    @Test
    void settlementDecrementsThePackAvailabilityAggregate() {
        UUID orgId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        seedWallet(orgId, 1_000L, 0L);
        setPackAvailability(orgId, "gpt", 500L);
        String holdId = admitHold(orgId, projectId, "gpt", 100L, 0L, 300L);

        service.settle(holdId, orgId, "gpt", 100L, 0L, 300L).block();

        assertThat(stringValue(GatewayRedisKeys.packAvailability(orgId, "gpt"))).isEqualTo(200L);
    }

    private String admit(UUID orgId, UUID projectId, String modelCode, long cash, long bonus, long units) {
        String holdId = UUID.randomUUID().toString();
        return service.admit(new PreHoldService.AdmitCommand(
                holdId, orgId, projectId, null, modelCode, cash, bonus, units,
                null, null, null, null, 50, properties.getBilling().getLeaseTtlSeconds()
        )).block();
    }

    private String admitHold(UUID orgId, UUID projectId, String modelCode, long cash, long bonus, long units) {
        String holdId = UUID.randomUUID().toString();
        String status = service.admit(new PreHoldService.AdmitCommand(
                holdId, orgId, projectId, null, modelCode, cash, bonus, units,
                null, null, null, null, 50, properties.getBilling().getLeaseTtlSeconds()
        )).block();
        assertThat(status).isEqualTo("OK");
        return holdId;
    }

    private void seedWallet(UUID orgId, long cash, long bonus) {
        redis.opsForHash().putAll(GatewayRedisKeys.wallet(orgId), Map.of(
                "cash_micros", String.valueOf(cash),
                "general_bonus_micros", String.valueOf(bonus),
                "status", "ACTIVE"
        )).block();
    }

    private void setPackAvailability(UUID orgId, String modelCode, long units) {
        redis.opsForValue().set(GatewayRedisKeys.packAvailability(orgId, modelCode), String.valueOf(units)).block();
    }

    private void expireNow(String holdId) {
        redis.opsForZSet()
                .add(GatewayRedisKeys.holdsExpiry(), holdId, System.currentTimeMillis() - 1_000L)
                .block();
    }

    private long wallet(UUID orgId, String field) {
        Object value = redis.opsForHash().get(GatewayRedisKeys.wallet(orgId), field).block();
        return value == null ? 0L : Long.parseLong(String.valueOf(value));
    }

    private long stringValue(String key) {
        String value = redis.opsForValue().get(key).block();
        return value == null ? 0L : Long.parseLong(value);
    }

    private static RedisScript<String> admitScript() {
        return RedisScript.of(new ClassPathResource("lua/prehold_admit.lua"), String.class);
    }

    private static RedisScript<Long> renewScript() {
        return RedisScript.of(new ClassPathResource("lua/prehold_renew.lua"), Long.class);
    }

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> settleScript() {
        return RedisScript.of(new ClassPathResource("lua/prehold_settle.lua"), List.class);
    }

    private static RedisScript<String> releaseScript() {
        return RedisScript.of(new ClassPathResource("lua/prehold_release.lua"), String.class);
    }

    private static RedisScript<String> reclaimScript() {
        return RedisScript.of(new ClassPathResource("lua/prehold_reclaim.lua"), String.class);
    }

    private static final class RedisConnections {
        private RedisConnections() {
        }

        static void flush(ReactiveStringRedisTemplate template) {
            template.execute(connection -> connection.serverCommands().flushDb()).blockLast(Duration.ofSeconds(5));
        }
    }
}

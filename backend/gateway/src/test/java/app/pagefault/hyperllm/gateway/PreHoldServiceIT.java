package app.pagefault.hyperllm.gateway;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.domain.billing.AllocationLeg;
import app.pagefault.hyperllm.gateway.billing.PreHoldService;
import app.pagefault.hyperllm.gateway.config.GatewayProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the reservation lifecycle against a real Redis so the Lua atomicity is exercised, not mocked.
 *
 * <p>The reservation is per batch, so every assertion is about one batch: the freeze lands on the
 * batch the allocator picked, and a settlement charges that batch and no other. Expiry is simulated
 * by rewriting the sorted-set score instead of sleeping.
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
    private UUID orgId;
    private UUID projectId;
    private UUID packId;
    private UUID grantId;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(factory);
        redis.execute(connection -> connection.serverCommands().flushAll()).blockLast();

        GatewayProperties properties = new GatewayProperties();
        service = new PreHoldService(
                redis,
                properties,
                RedisScript.of(new ClassPathResource("lua/prehold_admit.lua"), String.class),
                RedisScript.of(new ClassPathResource("lua/prehold_renew.lua"), Long.class),
                RedisScript.of(new ClassPathResource("lua/prehold_settle.lua"), List.class),
                RedisScript.of(new ClassPathResource("lua/prehold_release.lua"), String.class),
                RedisScript.of(new ClassPathResource("lua/prehold_reclaim.lua"), String.class),
                RedisScript.of(new ClassPathResource("lua/prehold_mark_served.lua"), Long.class));

        orgId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        packId = UUID.randomUUID();
        grantId = UUID.randomUUID();
        seedWallet(10_000L);
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    @Test
    void freezesEveryBatchTheAllocatorPicked() {
        seedPack(100L);
        seedGrant(5_000L);

        assertThat(admit("h1", List.of(
                AllocationLeg.pack(packId, 10L, 500L),
                AllocationLeg.bonus(grantId, 200L),
                AllocationLeg.cash(300L)))).isEqualTo("OK");

        assertThat(hash(packItem(), "held_units")).isEqualTo("10");
        assertThat(hash(bonusItem(), "held_micros")).isEqualTo("200");
        assertThat(hash(wallet(), "held_cash_micros")).isEqualTo("300");
        assertThat(redis.opsForHash().get(GatewayRedisKeys.hold("h1"), "legs").block())
                .isNotNull();
    }

    @Test
    void refusesWhenABatchIsNotMirrored() {
        assertThat(admit("h1", List.of(AllocationLeg.pack(packId, 10L, 500L))))
                .isEqualTo("MISS_ENTITLEMENTS");
        // Nothing was frozen before the script gave up.
        assertThat(hash(wallet(), "held_cash_micros")).isEqualTo("0");
        assertThat(redis.opsForZSet().score(GatewayRedisKeys.holdsExpiry(), "h1").block()).isNull();
    }

    @Test
    void refusesWhenTheBatchCannotCoverTheUnits() {
        seedPack(5L);

        assertThat(admit("h1", List.of(AllocationLeg.pack(packId, 10L, 500L))))
                .isEqualTo("INSUFFICIENT_ENTITLEMENT");
        assertThat(hash(packItem(), "held_units")).isEqualTo("0");
    }

    @Test
    void refusesWhenCashCannotCoverTheRemainder() {
        seedPack(0L);
        seedWallet(100L);

        assertThat(admit("h1", List.of(AllocationLeg.cash(300L))))
                .isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    void settlementReleasesTheFreezeAndChargesTheBatches() {
        seedPack(100L);
        seedGrant(5_000L);
        admit("h1", List.of(
                AllocationLeg.pack(packId, 10L, 500L),
                AllocationLeg.bonus(grantId, 200L),
                AllocationLeg.cash(300L)));

        PreHoldService.SettleOutcome outcome = service.settle(
                        "h1", orgId,
                        List.of(AllocationLeg.pack(packId, 8L, 400L),
                                AllocationLeg.bonus(grantId, 100L),
                                AllocationLeg.cash(200L)),
                        "{}", 3600L, 300_000L, false, 300L)
                .block();

        assertThat(outcome.cashCharged()).isEqualTo(200L);
        assertThat(outcome.bonusCharged()).isEqualTo(100L);
        assertThat(hash(packItem(), "held_units")).isEqualTo("0");
        assertThat(hash(packItem(), "remaining_units")).isEqualTo("92");
        assertThat(hash(bonusItem(), "held_micros")).isEqualTo("0");
        assertThat(hash(bonusItem(), "remaining_micros")).isEqualTo("4900");
        assertThat(hash(wallet(), "held_cash_micros")).isEqualTo("0");
        assertThat(hash(wallet(), "cash_micros")).isEqualTo("9800");
    }

    @Test
    void settlementIsIdempotent() {
        seedPack(100L);
        admit("h1", List.of(AllocationLeg.pack(packId, 10L, 500L)));
        List<AllocationLeg> charges = List.of(AllocationLeg.pack(packId, 10L, 500L));

        service.settle("h1", orgId, charges, "{}", 3600L, 300_000L, false, 300L).block();
        PreHoldService.SettleOutcome second =
                service.settle("h1", orgId, charges, "{}", 3600L, 300_000L, false, 300L).block();

        assertThat(second.alreadySettled()).isTrue();
        assertThat(hash(packItem(), "remaining_units")).isEqualTo("90");
    }

    @Test
    void releaseReturnsTheFreezeWithoutCharging() {
        seedPack(100L);
        admit("h1", List.of(AllocationLeg.pack(packId, 10L, 500L), AllocationLeg.cash(0L)));

        service.release("h1", orgId, 3600L).block();

        assertThat(hash(packItem(), "held_units")).isEqualTo("0");
        assertThat(hash(packItem(), "remaining_units")).isEqualTo("100");
    }

    @Test
    void reclaimOnlyReleasesAHoldWhoseLeaseExpired() {
        seedPack(100L);
        admit("h1", List.of(AllocationLeg.pack(packId, 10L, 500L)));

        assertThat(service.reclaimExpired(10).block()).isZero();
        assertThat(hash(packItem(), "held_units")).isEqualTo("10");

        redis.opsForZSet().add(GatewayRedisKeys.holdsExpiry(), "h1", 1d).block();

        assertThat(service.reclaimExpired(10).block()).isEqualTo(1);
        assertThat(hash(packItem(), "held_units")).isEqualTo("0");
    }

    @Test
    void concurrentAdmissionsCannotOversellOneBatch() {
        seedPack(40L);

        List<String> verdicts = Flux.range(1, 50)
                .flatMap(index -> admitAsync("h" + index, List.of(AllocationLeg.pack(packId, 1L, 10L))))
                .collectList()
                .block();

        assertThat(verdicts.stream().filter("OK"::equals).count()).isEqualTo(40);
        assertThat(hash(packItem(), "held_units")).isEqualTo("40");
    }

    private String admit(String holdId, List<AllocationLeg> legs) {
        return admitAsync(holdId, legs).block();
    }

    private reactor.core.publisher.Mono<String> admitAsync(String holdId, List<AllocationLeg> legs) {
        return service.admit(new PreHoldService.AdmitCommand(
                holdId, orgId, projectId, null, "gpt", legs,
                null, null, null, null, 0, 300L, false));
    }

    private void seedWallet(long cash) {
        redis.opsForHash().putAll(wallet(), Map.of(
                "cash_micros", String.valueOf(cash),
                "held_cash_micros", "0",
                "status", "ACTIVE")).block();
    }

    private void seedPack(long remaining) {
        long expiresAt = System.currentTimeMillis() + 60_000L;
        redis.opsForZSet().add(GatewayRedisKeys.pack(orgId, "gpt"), packId.toString(), expiresAt).block();
        redis.opsForHash().putAll(packItem(), Map.of(
                "remaining_units", String.valueOf(remaining),
                "held_units", "0",
                "unit_type", "TOKENS",
                "expires_at", String.valueOf(expiresAt))).block();
    }

    private void seedGrant(long remaining) {
        long expiresAt = System.currentTimeMillis() + 60_000L;
        redis.opsForZSet().add(GatewayRedisKeys.bonus(orgId), grantId.toString(), expiresAt).block();
        redis.opsForHash().putAll(bonusItem(), Map.of(
                "remaining_micros", String.valueOf(remaining),
                "held_micros", "0",
                "expires_at", String.valueOf(expiresAt))).block();
    }

    private String wallet() {
        return GatewayRedisKeys.wallet(orgId);
    }

    private String packItem() {
        return GatewayRedisKeys.packItem(packId);
    }

    private String bonusItem() {
        return GatewayRedisKeys.bonusItem(grantId);
    }

    private String hash(String key, String field) {
        Object value = redis.opsForHash().get(key, field).block();
        return value == null ? null : String.valueOf(value);
    }
}

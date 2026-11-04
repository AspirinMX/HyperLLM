package app.pagefault.hyperllm.gateway;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.common.messaging.BillingMoneyEvent;
import app.pagefault.hyperllm.gateway.outbox.OutboxQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private OutboxQueue queue;
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
        queue = new OutboxQueue(properties);
        service = new PreHoldService(
                redis,
                properties,
                RedisScript.of(new ClassPathResource("lua/prehold_admit.lua"), String.class),
                RedisScript.of(new ClassPathResource("lua/prehold_renew.lua"), Long.class),
                RedisScript.of(new ClassPathResource("lua/prehold_settle.lua"), List.class),
                RedisScript.of(new ClassPathResource("lua/prehold_mark_served.lua"), Long.class),
                queue);

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
        assertThat(redis.opsForHash().get(GatewayRedisKeys.hold(id("h1")), "legs").block())
                .isNotNull();
    }

    @Test
    void refusesWhenABatchIsNotMirrored() {
        assertThat(admit("h1", List.of(AllocationLeg.pack(packId, 10L, 500L))))
                .isEqualTo("MISS_ENTITLEMENTS");
        // Nothing was frozen before the script gave up.
        assertThat(hash(wallet(), "held_cash_micros")).isEqualTo("0");
        assertThat(redis.opsForZSet().score(GatewayRedisKeys.holdsExpiry(), id("h1")).block()).isNull();
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

        PreHoldService.SettleOutcome outcome = settle("h1", List.of(
                AllocationLeg.pack(packId, 8L, 400L), AllocationLeg.bonus(grantId, 100L), AllocationLeg.cash(200L)));

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

        settle("h1", charges);
        PreHoldService.SettleOutcome second = settle("h1", charges);

        assertThat(second.alreadySettled()).isTrue();
        assertThat(hash(packItem(), "remaining_units")).isEqualTo("90");
    }

    @Test
    void releaseReturnsTheFreezeWithoutCharging() {
        seedPack(100L);
        admit("h1", List.of(AllocationLeg.pack(packId, 10L, 500L), AllocationLeg.cash(0L)));

        service.release(lease("h1")).block();

        assertThat(hash(packItem(), "held_units")).isEqualTo("0");
        assertThat(hash(packItem(), "remaining_units")).isEqualTo("100");
    }

    @Test
    void reclaimOnlyReleasesAHoldWhoseLeaseExpired() {
        seedPack(100L);
        admit("h1", List.of(AllocationLeg.pack(packId, 10L, 500L)));

        assertThat(service.reclaimExpired(10).block()).isZero();
        assertThat(hash(packItem(), "held_units")).isEqualTo("10");

        redis.opsForZSet().add(GatewayRedisKeys.holdsExpiry(), id("h1"), 1d).block();

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

    @Test
    void reservesTokensPerWindowAndCorrectsThemToTheActualUsage() {
        seedWallet(10_000);
        assertThat(admitWithTokens("h1", 100, 60)).isEqualTo("OK");
        String bucket = hash(GatewayRedisKeys.hold(id("h1")), "rate_second");
        assertThat(admitWithTokens("h2", 100, 60))
                .as("the second request would exceed the token window")
                .isEqualTo("RATE_LIMIT");
        assertThat(hash(GatewayRedisKeys.rateCounts("key", projectId), bucket))
                .as("a rejected request must not be counted")
                .isEqualTo("1");

        // The first request used far fewer tokens than reserved; the window is corrected once.
        settle("h1", List.of());
        assertThat(hash(GatewayRedisKeys.rateTokens("key", projectId), bucket)).isEqualTo("0");
        assertThat(admitWithTokens("h3", 100, 60)).isEqualTo("OK");
    }

    @Test
    void rpmIsCountedInOneSecondBucketsAndExpiresFromTheWindow() {
        seedWallet(10_000);
        assertThat(admitWithRpm("h1", 1)).isEqualTo("OK");
        String bucket = hash(GatewayRedisKeys.hold(id("h1")), "rate_second");
        assertThat(admitWithRpm("h2", 1)).isEqualTo("RATE_LIMIT");
        assertThat(hash(GatewayRedisKeys.rateCounts("key", projectId), bucket)).isEqualTo("1");

        // Dropping the whole window is what the 60 second boundary does to the buckets.
        redis.delete(GatewayRedisKeys.rateWindow("key", projectId),
                GatewayRedisKeys.rateCounts("key", projectId),
                GatewayRedisKeys.rateTokens("key", projectId)).block();
        assertThat(admitWithRpm("h3", 1)).isEqualTo("OK");
    }

    @Test
    void servedReclaimChargesOnlyTheFloorAndCopiesTheSameAppliedEventToTheOutbox() throws Exception {
        seedPack(100L);
        seedGrant(5_000L);
        var held = List.of(AllocationLeg.pack(packId, 10, 500),
                AllocationLeg.bonus(grantId, 400), AllocationLeg.cash(600));
        var floor = List.of(AllocationLeg.pack(packId, 3, 150),
                AllocationLeg.bonus(grantId, 100), AllocationLeg.cash(200));
        assertThat(service.admit(new PreHoldService.AdmitCommand(id("h1"), orgId, projectId, null,
                "gpt", held, null, null, null, null, 0L, null, null, 1, 300L,
                "request-1", floor, "")).block()).isEqualTo("OK");
        service.markServed(lease("h1")).block();
        redis.opsForZSet().add(GatewayRedisKeys.holdsExpiry(), id("h1"), 1d).block();

        assertThat(service.reclaimExpired(10).block()).isEqualTo(1);
        assertThat(settle("h1", held).alreadySettled()).isTrue();
        assertThat(service.reclaimExpired(10).block()).isZero();
        assertThat(hash(packItem(), "remaining_units")).isEqualTo("97");
        assertThat(hash(bonusItem(), "remaining_micros")).isEqualTo("4900");
        assertThat(hash(wallet(), "cash_micros")).isEqualTo("9800");
        assertThat(hash(packItem(), "held_units")).isEqualTo("0");
        assertThat(hash(bonusItem(), "held_micros")).isEqualTo("0");
        assertThat(hash(wallet(), "held_cash_micros")).isEqualTo("0");
        assertThat(redis.opsForValue().get(GatewayRedisKeys.concurrencyCounter("org", orgId)).block()).isEqualTo("0");
        assertThat(redis.opsForHash().get(GatewayRedisKeys.ledgerPosition(orgId), "produced").block()).isEqualTo(id("h1"));

        var written = queue.takeBatch(10, 0);
        assertThat(written).hasSize(1);
        String copy = redis.opsForValue().get(GatewayRedisKeys.settleEvent(id("h1"))).block();
        assertThat(written.getFirst().payload()).isEqualTo(copy);
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var event = mapper.readValue(copy, BillingMoneyEvent.class);
        assertThat(event.settledAfterReclaim()).isTrue();
        assertThat(event.idempotencyKey()).isEqualTo("req:request-1");
        assertThat(event.cashDeltaMicros()).isEqualTo(-200L);
        assertThat(event.bonusDeltaMicros()).isEqualTo(-100L);
        assertThat(event.packDeltaUnits()).isEqualTo(-3L);
        assertThat(mapper.readTree(event.allocationDetailJson()).at("/totals/chargedMicros").asLong()).isEqualTo(450L);
    }

    @Test
    void cashShortfallLeavesOtherReservationsAndBooksOnlyTheAppliedAmount() throws Exception {
        seedWallet(500);
        admit("h1", List.of(AllocationLeg.cash(100)));
        admit("h2", List.of(AllocationLeg.cash(300)));
        var outcome = settle("h1", List.of(AllocationLeg.cash(400)));
        assertThat(outcome.cashCharged()).isEqualTo(200);
        assertThat(outcome.shortfallMicros()).isEqualTo(200);
        assertThat(hash(wallet(), "cash_micros")).isEqualTo("300");
        assertThat(hash(wallet(), "held_cash_micros")).isEqualTo("300");
        var event = new ObjectMapper().registerModule(new JavaTimeModule()).readValue(
                queue.takeBatch(10, 0).getFirst().payload(), BillingMoneyEvent.class);
        assertThat(event.legs()).containsExactly(BillingMoneyEvent.Leg.cash(200));
        assertThat(event.cashDeltaMicros()).isEqualTo(-200);
    }

    @Test
    void missingBatchNeverReappearsDuringSettlement() {
        seedPack(100);
        admit("h1", List.of(AllocationLeg.pack(packId, 1, 100), AllocationLeg.cash(100)));
        redis.delete(packItem()).block();
        assertThat(settle("h1", List.of(AllocationLeg.cash(100))).status()).isEqualTo("MISS_ENTITLEMENTS");
        assertThat(redis.hasKey(packItem()).block()).isFalse();
        assertThat(hash(wallet(), "held_cash_micros")).isEqualTo("0");
        assertThat(hash(wallet(), "cash_micros")).isEqualTo("10000");
        assertThat(queue.depth()).isZero();
    }

    @Test
    void hardCapRefusesTheRequestThatWouldExceedTheBudget() {
        String month = month();
        seedBudget(month, 0L, 0L, 1_000L, 0L, "CONFIRMED");

        assertThat(admitWithBudget("h1", 600L, 400L, 1_000L, 0L)).isEqualTo("OK");
        assertThat(admitWithBudget("h2", 600L, 400L, 1_000L, 0L))
                .as("600 already frozen plus 600 more is over the 1000 hard cap")
                .isEqualTo("BUDGET_EXCEEDED");

        assertThat(hash(budgetKey(month), "held_micros")).isEqualTo("600");
        assertThat(hash(budgetKey(month), "used_micros")).isEqualTo("0");
        assertThat(hash(budgetSignal(month), "rejections")).isEqualTo("1");
        assertThat(hash(wallet(), "held_cash_micros"))
                .as("a rejected request reserves no wallet money either")
                .isEqualTo("1");
    }

    @Test
    void settlementMovesTheBudgetFreezeToUsedAndCarriesItOnTheEvent() throws Exception {
        String month = month();
        seedBudget(month, 0L, 0L, 10_000L, 5_000L, "CONFIRMED");
        admitWithBudget("h1", 600L, 100L, 10_000L, 5_000L);

        PreHoldService.SettleOutcome outcome = settleWithBudget("h1",
                List.of(AllocationLeg.cash(200L)), 400L);

        assertThat(outcome.cashCharged()).isEqualTo(200L);
        assertThat(hash(budgetKey(month), "held_micros")).isEqualTo("0");
        assertThat(hash(budgetKey(month), "used_micros")).isEqualTo("400");
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var event = mapper.readValue(queue.takeBatch(10, 0).getFirst().payload(), BillingMoneyEvent.class);
        assertThat(event.budgetMonth()).isEqualTo(month);
        assertThat(event.budgetChargeMicros()).isEqualTo(400L);
    }

    @Test
    void releaseReturnsTheBudgetFreezeWithoutChargingIt() {
        String month = month();
        seedBudget(month, 0L, 0L, 10_000L, 0L, "CONFIRMED");
        admitWithBudget("h1", 600L, 100L, 10_000L, 0L);

        service.release(new PreHoldService.Lease(orgId, List.of(), id("h1"), 0L, budgetKey(month))).block();

        assertThat(hash(budgetKey(month), "held_micros")).isEqualTo("0");
        assertThat(hash(budgetKey(month), "used_micros")).isEqualTo("0");
    }

    @Test
    void unverifiedMonthClosesAHardCappedProjectUntilItIsResolved() {
        String month = month();
        seedBudget(month, 900L, 0L, 1_000L, 0L, "UNVERIFIED");

        assertThat(admitWithBudget("h1", 100L, 50L, 1_000L, 0L)).isEqualTo("BUDGET_UNAVAILABLE");
        assertThat(hash(wallet(), "held_cash_micros")).isEqualTo("0");
    }

    @Test
    void aMissingMonthAsksTheCallerToInstallIt() {
        assertThat(admitWithBudget("h1", 100L, 50L, 1_000L, 0L)).isEqualTo("MISS_BUDGET");
        assertThat(hash(wallet(), "held_cash_micros")).isEqualTo("0");
    }

    private PreHoldService.SettleOutcome settle(String key, List<AllocationLeg> charges) {
        return service.settle(lease(key), charges, "", OffsetDateTime.now()).block();
    }

    private PreHoldService.Lease lease(String key) {
        return new PreHoldService.Lease(orgId, List.of(), id(key), 0L);
    }

    private String admitWithBudget(String holdId, long estimate, long floor, long hardCap, long softCap) {
        String month = month();
        return service.admit(new PreHoldService.AdmitCommand(
                id(holdId), orgId, projectId, null, "gpt", List.of(AllocationLeg.cash(1)),
                null, null, null, null, 0L, null, null, 0, 300L, holdId, List.of(), "",
                month, budgetKey(month), estimate, hardCap, softCap, floor)).block();
    }

    private PreHoldService.SettleOutcome settleWithBudget(String key, List<AllocationLeg> charges,
                                                          long budgetCharge) {
        return service.settle(new PreHoldService.Lease(orgId, List.of(), id(key), 0L, budgetKey(month())),
                charges, "", OffsetDateTime.now(), 0L, budgetCharge).block();
    }

    private static String month() {
        return YearMonth.now(ZoneOffset.UTC).toString();
    }

    private String budgetKey(String month) {
        return GatewayRedisKeys.projectBudget(projectId, month);
    }

    private String budgetSignal(String month) {
        return GatewayRedisKeys.budgetSignal(projectId, month);
    }

    private void seedBudget(String month, long used, long held, long hardCap, long softCap, String state) {
        redis.opsForHash().putAll(GatewayRedisKeys.projectBudget(projectId, month), Map.of(
                "month", month,
                "used_micros", String.valueOf(used),
                "held_micros", String.valueOf(held),
                "hard_cap_micros", String.valueOf(hardCap),
                "soft_cap_micros", String.valueOf(softCap),
                "state", state,
                "start_at", OffsetDateTime.now(ZoneOffset.UTC).toString(),
                "loaded", "1")).block();
    }

    private static String id(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String admit(String holdId, List<AllocationLeg> legs) {
        return admitAsync(holdId, legs).block();
    }

    private String admitWithTokens(String holdId, int tokensLimit, long estimate) {
        return service.admit(new PreHoldService.AdmitCommand(
                id(holdId), orgId, projectId, null, "gpt", List.of(AllocationLeg.cash(1)),
                null, null, null, tokensLimit, estimate, null, null, 0, 300L,
                holdId, List.of(), "")).block();
    }

    private String admitWithRpm(String holdId, int rpmLimit) {
        return service.admit(new PreHoldService.AdmitCommand(
                id(holdId), orgId, projectId, null, "gpt", List.of(AllocationLeg.cash(1)),
                rpmLimit, null, null, null, 0L, null, null, 0, 300L,
                holdId, List.of(), "")).block();
    }


    private reactor.core.publisher.Mono<String> admitAsync(String holdId, List<AllocationLeg> legs) {
        return service.admit(new PreHoldService.AdmitCommand(
                id(holdId), orgId, projectId, null, "gpt", legs,
                null, null, null, null, 0L, null, null, 0, 300L, holdId, List.of(), ""));
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
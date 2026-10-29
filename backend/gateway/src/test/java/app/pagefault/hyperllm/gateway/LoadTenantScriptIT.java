package app.pagefault.hyperllm.gateway;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the load contract against a real Redis: it fills what is missing, it never overwrites what
 * exists, and it leaves a TTL on everything it created.
 */
@Tag("integration")
class LoadTenantScriptIT {

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    private static final long TTL_MILLIS = 300_000L;

    private LettuceConnectionFactory factory;
    private ReactiveStringRedisTemplate redis;
    private RedisScript<List> script;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(factory);
        redis.execute(connection -> connection.serverCommands().flushAll()).blockLast();
        script = RedisScript.of(new ClassPathResource("lua/load_tenant.lua"), List.class);
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    @Test
    void createsTheWalletTheBatchesAndTheirIndexes() {
        UUID orgId = UUID.randomUUID();
        UUID packId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        long expiresAt = System.currentTimeMillis() + 60_000L;

        load(orgId, "gpt", packId, grantId, 1_000L, 50L, 7_000L, expiresAt);

        assertThat(string(redis.opsForHash().get(GatewayRedisKeys.wallet(orgId), "cash_micros").block()))
                .isEqualTo("1000");
        assertThat(string(redis.opsForHash().get(GatewayRedisKeys.wallet(orgId), "status").block()))
                .isEqualTo("ACTIVE");
        assertThat(redis.opsForZSet().score(GatewayRedisKeys.pack(orgId, "gpt"), packId.toString()).block())
                .isEqualTo((double) expiresAt);
        assertThat(string(redis.opsForHash().get(GatewayRedisKeys.packItem(packId), "remaining_units").block()))
                .isEqualTo("50");
        assertThat(string(redis.opsForHash().get(GatewayRedisKeys.bonusItem(grantId), "remaining_micros").block()))
                .isEqualTo("7000");
        assertThat(pttlOf(GatewayRedisKeys.wallet(orgId))).isPositive();
        assertThat(pttlOf(GatewayRedisKeys.packItem(packId))).isPositive();
        assertThat(pttlOf(GatewayRedisKeys.bonus(orgId))).isPositive();
    }

    @Test
    void neverOverwritesAWalletThatAlreadyExists() {
        UUID orgId = UUID.randomUUID();
        UUID packId = UUID.randomUUID();
        seedWallet(orgId, 42L);

        List<String> comparison = load(orgId, "gpt", packId, UUID.randomUUID(),
                1_000L, 50L, 7_000L, System.currentTimeMillis() + 60_000L);

        assertThat(comparison).containsExactly("OK", "cash", "42", "1000", "bonus", "", "7000");
        assertThat(string(redis.opsForHash().get(GatewayRedisKeys.wallet(orgId), "cash_micros").block()))
                .isEqualTo("42");
        // The batch family was still missing, so it is filled even though the wallet was not.
        assertThat(string(redis.opsForHash().get(GatewayRedisKeys.packItem(packId), "remaining_units").block()))
                .isEqualTo("50");
    }

    @Test
    void neverOverwritesABatchThatAlreadyExists() {
        UUID orgId = UUID.randomUUID();
        UUID packId = UUID.randomUUID();
        redis.opsForZSet().add(GatewayRedisKeys.pack(orgId, "gpt"), packId.toString(), 1L).block();
        redis.opsForHash().putAll(GatewayRedisKeys.packItem(packId), Map.of("remaining_units", "3")).block();

        load(orgId, "gpt", packId, UUID.randomUUID(), 1_000L, 50L, 7_000L, System.currentTimeMillis() + 60_000L);

        assertThat(string(redis.opsForHash().get(GatewayRedisKeys.packItem(packId), "remaining_units").block()))
                .isEqualTo("3");
        assertThat(redis.opsForZSet().score(GatewayRedisKeys.pack(orgId, "gpt"), packId.toString()).block())
                .isEqualTo(1.0d);
    }

    @Test
    void rejectsAColdSnapshotTakenBeforeTheLastAuthorizedCredit() {
        UUID orgId = UUID.randomUUID();
        var keys = List.of(GatewayRedisKeys.wallet(orgId), GatewayRedisKeys.pack(orgId, "gpt"),
                GatewayRedisKeys.bonus(orgId), GatewayRedisKeys.ledgerPosition(orgId));
        redis.opsForHash().put(keys.getLast(), "credit_version", "9007199254740993").block();
        var args = new ArrayList<>(List.of("300000", "17|0|ACTIVE|9007199254740992", "0", "0",
                GatewayRedisKeys.packItemPrefix(), GatewayRedisKeys.bonusItemPrefix()));

        assertThat(redis.execute(script, keys, args).blockLast()).containsExactly("STALE_CREDIT");
        assertThat(redis.hasKey(keys.getFirst()).block()).isFalse();
        args.set(1, "41|0|ACTIVE|9007199254740993");
        redis.execute(script, keys, args).blockLast();
        assertThat(redis.opsForHash().get(keys.getFirst(), "cash_micros").block()).isEqualTo("41");
    }

    private List<String> load(UUID orgId, String modelCode, UUID packId, UUID grantId,
                      long cash, long packUnits, long grantMicros, long expiresAt) {
        List<String> keys = List.of(
                GatewayRedisKeys.wallet(orgId),
                GatewayRedisKeys.pack(orgId, modelCode),
                GatewayRedisKeys.bonus(orgId));
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(TTL_MILLIS));
        args.add(cash + "|0|ACTIVE");
        args.add("1");
        args.add(packId + "|" + packUnits + "|0|TOKENS|" + expiresAt);
        args.add("1");
        args.add(grantId + "|" + grantMicros + "|0|" + expiresAt);
        args.add(GatewayRedisKeys.packItemPrefix());
        args.add(GatewayRedisKeys.bonusItemPrefix());
        List<?> result = redis.execute(script, keys, args).blockLast();
        return result.stream().map(LoadTenantScriptIT::string).toList();
    }

    private void seedWallet(UUID orgId, long cash) {
        redis.opsForHash().putAll(GatewayRedisKeys.wallet(orgId), Map.of(
                "cash_micros", String.valueOf(cash),
                "held_cash_micros", "0",
                "status", "ACTIVE")).block();
    }

    private long pttlOf(String key) {
        Long ttl = redis
                .execute(connection -> connection.keyCommands().pTtl(ByteBuffer.wrap(key.getBytes(StandardCharsets.UTF_8))))
                .blockFirst();
        return ttl == null ? -2L : ttl;
    }

    private static String string(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }
}

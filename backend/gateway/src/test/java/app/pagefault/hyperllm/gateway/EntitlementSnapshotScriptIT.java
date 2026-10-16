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
 * Runs the snapshot script against a real Redis: the flat reply format, the expiry filter and the
 * optional TTL renewal are all things only a server can confirm.
 */
@Tag("integration")
class EntitlementSnapshotScriptIT {

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
        script = RedisScript.of(new ClassPathResource("lua/prehold_snapshot.lua"), List.class);
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    @Test
    void returnsTheWalletAndEveryLiveLegInOneCall() {
        UUID orgId = UUID.randomUUID();
        UUID packId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        seedWallet(orgId, 1_000L, 200L);
        seedPack(orgId, "gpt", packId, 50L, 5L, System.currentTimeMillis() + 60_000L);
        seedGrant(orgId, grantId, 7_000L, 500L, 4_102_444_800_000L);

        Snapshot snapshot = read(orgId, "gpt", /* renew= */ false);

        assertThat(snapshot.missWallet()).isFalse();
        assertThat(snapshot.cashMicros()).isEqualTo(1_000L);
        assertThat(snapshot.heldCashMicros()).isEqualTo(200L);
        assertThat(snapshot.status()).isEqualTo("ACTIVE");
        assertThat(snapshot.packs()).hasSize(1);
        assertThat(snapshot.packs().get(0)).isEqualTo(new PackLeg(packId.toString(), 50L, 5L, "TOKENS"));
        assertThat(snapshot.grants()).hasSize(1);
        assertThat(snapshot.grants().get(0)).isEqualTo(new GrantLeg(grantId.toString(), 7_000L, 500L));
    }

    @Test
    void reportsAMissingMirrorInsteadOfAnEmptyWallet() {
        Snapshot snapshot = read(UUID.randomUUID(), "gpt", /* renew= */ false);

        assertThat(snapshot.missWallet()).isTrue();
        assertThat(snapshot.packs()).isEmpty();
        assertThat(snapshot.grants()).isEmpty();
    }

    @Test
    void hidesBatchesThatAlreadyExpired() {
        UUID orgId = UUID.randomUUID();
        UUID live = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        seedWallet(orgId, 10L, 0L);
        seedPack(orgId, "gpt", live, 1L, 0L, System.currentTimeMillis() + 60_000L);
        seedPack(orgId, "gpt", expired, 9L, 0L, System.currentTimeMillis() - 1_000L);

        Snapshot snapshot = read(orgId, "gpt", /* renew= */ false);

        assertThat(snapshot.packs()).extracting(PackLeg::id).containsExactly(live.toString());
    }

    @Test
    void renewsEveryKeyItTouchedWhenAsked() {
        UUID orgId = UUID.randomUUID();
        UUID packId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        seedWallet(orgId, 100L, 0L);
        seedPack(orgId, "gpt", packId, 1L, 0L, System.currentTimeMillis() + 60_000L);
        seedGrant(orgId, grantId, 1L, 0L, 4_102_444_800_000L);

        read(orgId, "gpt", /* renew= */ true);

        assertThat(pttlOf(GatewayRedisKeys.wallet(orgId))).isPositive();
        assertThat(pttlOf(GatewayRedisKeys.pack(orgId, "gpt"))).isPositive();
        assertThat(pttlOf(GatewayRedisKeys.bonus(orgId))).isPositive();
        assertThat(pttlOf(GatewayRedisKeys.packItem(packId))).isPositive();
        assertThat(pttlOf(GatewayRedisKeys.bonusItem(grantId))).isPositive();
    }

    @Test
    void leavesTtlsAloneWhenNotAsked() {
        UUID orgId = UUID.randomUUID();
        UUID packId = UUID.randomUUID();
        seedWallet(orgId, 100L, 0L);
        seedPack(orgId, "gpt", packId, 1L, 0L, System.currentTimeMillis() + 60_000L);

        read(orgId, "gpt", /* renew= */ false);

        assertThat(pttlOf(GatewayRedisKeys.wallet(orgId))).isEqualTo(-1L);
    }

    private Snapshot read(UUID orgId, String modelCode, boolean renew) {
        List<String> keys = List.of(
                GatewayRedisKeys.wallet(orgId),
                GatewayRedisKeys.pack(orgId, modelCode),
                GatewayRedisKeys.bonus(orgId));
        List<String> args = List.of(
                String.valueOf(System.currentTimeMillis()),
                renew ? "1" : "0",
                String.valueOf(TTL_MILLIS),
                GatewayRedisKeys.packItemPrefix(),
                GatewayRedisKeys.bonusItemPrefix());
        List<?> raw = redis.execute(script, keys, args).next().block();
        return Snapshot.parse(text(raw));
    }

    /** Raw PTTL: {@code -1} means the key has no expiry, {@code -2} that it is gone. */
    private long pttlOf(String key) {
        Long ttl = redis
                .execute(connection -> connection.keyCommands().pTtl(ByteBuffer.wrap(key.getBytes(StandardCharsets.UTF_8))))
                .blockFirst();
        return ttl == null ? -2L : ttl;
    }

    private void seedWallet(UUID orgId, long cash, long heldCash) {
        redis.opsForHash().putAll(GatewayRedisKeys.wallet(orgId), Map.of(
                "cash_micros", String.valueOf(cash),
                "held_cash_micros", String.valueOf(heldCash),
                "status", "ACTIVE")).block();
    }

    private void seedPack(UUID orgId, String modelCode, UUID packId, long remaining, long held, long expiresAt) {
        redis.opsForZSet().add(GatewayRedisKeys.pack(orgId, modelCode), packId.toString(), expiresAt).block();
        redis.opsForHash().putAll(GatewayRedisKeys.packItem(packId), Map.of(
                "remaining_units", String.valueOf(remaining),
                "held_units", String.valueOf(held),
                "unit_type", "TOKENS",
                "expires_at", String.valueOf(expiresAt))).block();
    }

    private void seedGrant(UUID orgId, UUID grantId, long remaining, long held, long expiresAt) {
        redis.opsForZSet().add(GatewayRedisKeys.bonus(orgId), grantId.toString(), expiresAt).block();
        redis.opsForHash().putAll(GatewayRedisKeys.bonusItem(grantId), Map.of(
                "remaining_micros", String.valueOf(remaining),
                "held_micros", String.valueOf(held),
                "expires_at", String.valueOf(expiresAt))).block();
    }

    private static List<String> text(List<?> raw) {
        List<String> values = new ArrayList<>();
        if (raw == null) {
            return values;
        }
        for (Object element : raw) {
            if (element instanceof byte[] bytes) {
                values.add(new String(bytes, StandardCharsets.UTF_8));
            } else {
                values.add(String.valueOf(element));
            }
        }
        return values;
    }

    record PackLeg(String id, long remaining, long held, String unitType) {
    }

    record GrantLeg(String id, long remaining, long held) {
    }

    record Snapshot(boolean missWallet, long cashMicros, long heldCashMicros, String status,
                    List<PackLeg> packs, List<GrantLeg> grants) {

        static Snapshot parse(List<String> values) {
            boolean miss = !values.isEmpty() && "MISS_WALLET".equals(values.get(0));
            List<PackLeg> packs = new ArrayList<>();
            List<GrantLeg> grants = new ArrayList<>();
            if (miss) {
                return new Snapshot(true, 0L, 0L, "", packs, grants);
            }
            long cash = Long.parseLong(values.get(1));
            long heldCash = Long.parseLong(values.get(2));
            String status = values.get(3);
            int cursor = 4;
            int packCount = Integer.parseInt(values.get(cursor++));
            for (int i = 0; i < packCount; i++) {
                packs.add(new PackLeg(
                        values.get(cursor),
                        Long.parseLong(values.get(cursor + 1)),
                        Long.parseLong(values.get(cursor + 2)),
                        values.get(cursor + 3)));
                cursor += 5;
            }
            int grantCount = Integer.parseInt(values.get(cursor++));
            for (int i = 0; i < grantCount; i++) {
                grants.add(new GrantLeg(
                        values.get(cursor),
                        Long.parseLong(values.get(cursor + 1)),
                        Long.parseLong(values.get(cursor + 2))));
                cursor += 4;
            }
            return new Snapshot(false, cash, heldCash, status, packs, grants);
        }
    }
}

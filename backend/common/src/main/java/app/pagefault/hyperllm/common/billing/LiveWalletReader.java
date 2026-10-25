package app.pagefault.hyperllm.common.billing;

import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.UUID;

/**
 * Reads a tenant's live balance from the same place the gateway charges it.
 *
 * <p>Reading the database would show a value that trails every in-flight request and ignores the
 * reservations already made. Both control planes need the same answer to "what can this tenant
 * spend", so the read lives here rather than being written once per service.
 *
 * <p>The mirror reports whether the batches were ever filled. A wallet hash written by a control
 * plane is not a complete mirror, and serving its cash as if the bonus were zero would hide money
 * the tenant owns, so an unfilled tenant reads as unknown rather than as an empty balance.
 */
public class LiveWalletReader {

    /** Live state of one tenant: what is there, what is reserved, and what is left. */
    public record LiveWallet(long cashMicros, long bonusMicros, long heldMicros) {

        public long availableMicros() {
            return Math.max(0L, cashMicros + bonusMicros - heldMicros);
        }
    }

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> snapshotScript;

    @SuppressWarnings("rawtypes")
    public LiveWalletReader(StringRedisTemplate redisTemplate, RedisScript<List> snapshotScript) {
        this.redisTemplate = redisTemplate;
        this.snapshotScript = snapshotScript;
    }

    /** @return the live balance, or {@code null} when the tenant has not been fully mirrored */
    public LiveWallet read(UUID orgId) {
        List<String> keys = List.of(
                GatewayRedisKeys.wallet(orgId),
                GatewayRedisKeys.pack(orgId, ""),
                GatewayRedisKeys.bonus(orgId));
        List<String> args = List.of(
                String.valueOf(System.currentTimeMillis()),
                "0",
                "0",
                GatewayRedisKeys.packItemPrefix(),
                GatewayRedisKeys.bonusItemPrefix());
        // The blocking template takes the arguments as varargs; passing the list would serialise the
        // list itself as a single argument.
        List<?> raw = redisTemplate.execute(snapshotScript, keys, args.toArray());
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<String> values = raw.stream().map(String::valueOf).toList();
        if ("MISS_WALLET".equals(values.get(0))) {
            return null;
        }
        long cash = parse(values.get(1));
        long heldCash = parse(values.get(2));
        int cursor = 4;
        int packCount = (int) parse(values.get(cursor++));
        cursor += packCount * 5;
        int grantCount = (int) parse(values.get(cursor++));
        long bonus = 0L;
        long heldBonus = 0L;
        for (int i = 0; i < grantCount; i++) {
            bonus += parse(values.get(cursor + 1));
            heldBonus += parse(values.get(cursor + 2));
            cursor += 4;
        }
        boolean loaded = cursor < values.size() && "1".equals(values.get(cursor));
        if (!loaded) {
            return null;
        }
        return new LiveWallet(cash, bonus, heldCash + heldBonus);
    }

    private static long parse(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}

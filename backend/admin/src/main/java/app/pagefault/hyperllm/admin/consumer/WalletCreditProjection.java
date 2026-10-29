package app.pagefault.hyperllm.admin.consumer;

import app.pagefault.hyperllm.common.billing.LegacyBonusBatch;
import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.common.messaging.BillingMoneyEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class WalletCreditProjection {

    private static final RedisScript<String> CREDIT =
            RedisScript.of(new ClassPathResource("lua/wallet_credit.lua"), String.class);

    private final StringRedisTemplate redis;

    /** Called while holding the SQL wallet lock, after validating the transactional ledger fold. */
    public void apply(BillingMoneyEvent event, long version) {
        String result = redis.execute(CREDIT, List.of(GatewayRedisKeys.wallet(event.orgId()),
                        GatewayRedisKeys.bonus(event.orgId()), GatewayRedisKeys.bonusItem(event.eventId()),
                        GatewayRedisKeys.ledgerPosition(event.orgId())), event.eventId().toString(),
                Long.toString(event.cashDeltaMicros()), Long.toString(event.bonusDeltaMicros()),
                Long.toString(LegacyBonusBatch.NO_EXPIRY_EPOCH_MS), Long.toString(version));
        if (!"PROJECTED".equals(result) && !"COLD".equals(result)) {
            throw new IllegalStateException("Unable to project credit " + event.eventId());
        }
    }

}

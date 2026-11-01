package app.pagefault.hyperllm.admin.consumer;

import app.pagefault.hyperllm.common.billing.LegacyBonusBatch;
import app.pagefault.hyperllm.common.cache.GatewayRedisKeys;
import app.pagefault.hyperllm.common.messaging.BillingMoneyEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class WalletCreditProjection {

    private static final RedisScript<String> CREDIT =
            RedisScript.of(new ClassPathResource("lua/wallet_credit.lua"), String.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    /** Called while holding the SQL wallet lock, after validating the transactional ledger fold. */
    public void apply(BillingMoneyEvent event, long version) {
        BillingMoneyEvent.Grant grant = event.grant();
        String kind = grant == null
                ? (event.bonusDeltaMicros() > 0L ? "BONUS" : "CASH")
                : grant.type().name();
        UUID batchId = grant != null && grant.batchId() != null ? grant.batchId() : event.eventId();
        long expiresAt = grant != null && grant.expiresAt() != null
                ? grant.expiresAt().toInstant().toEpochMilli()
                : LegacyBonusBatch.NO_EXPIRY_EPOCH_MS;
        String scope = grant == null ? "*" : encodeScope(grant.applicableModels());
        String unitType = grant == null || grant.unitType() == null ? "" : grant.unitType();
        long units = grant == null ? 0L : grant.units();

        List<String> keys = new ArrayList<>();
        keys.add(GatewayRedisKeys.wallet(event.orgId()));
        keys.add(GatewayRedisKeys.bonus(event.orgId()));
        keys.add(switch (kind) {
            case "CASH" -> GatewayRedisKeys.wallet(event.orgId());
            case "BONUS" -> GatewayRedisKeys.bonusItem(batchId);
            default -> GatewayRedisKeys.packItem(batchId);
        });
        keys.add(GatewayRedisKeys.ledgerPosition(event.orgId()));
        if ("PACK".equals(kind) && grant != null && grant.applicableModels() != null) {
            for (String model : grant.applicableModels()) {
                keys.add(GatewayRedisKeys.pack(event.orgId(), model));
            }
        }

        String result = redis.execute(CREDIT, keys, event.eventId().toString(), kind,
                Long.toString(event.cashDeltaMicros()), Long.toString(event.bonusDeltaMicros()),
                Long.toString(expiresAt), Long.toString(version), scope, unitType,
                Long.toString(units));
        if (!"PROJECTED".equals(result) && !"COLD".equals(result)) {
            throw new IllegalStateException("Unable to project credit " + event.eventId() + ": " + result);
        }
    }

    private String encodeScope(List<String> models) {
        if (models == null) {
            return "*";
        }
        try {
            return mapper.writeValueAsString(models);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot encode grant scope", ex);
        }
    }
}

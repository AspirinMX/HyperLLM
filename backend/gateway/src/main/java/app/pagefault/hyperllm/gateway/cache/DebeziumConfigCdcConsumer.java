package app.pagefault.hyperllm.gateway.cache;

import app.pagefault.hyperllm.common.messaging.KafkaTopics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes Debezium Postgres CDC events on {@code cdc.gateway.config} with a per-instance
 * consumer group and evicts local Caffeine L1 caches in sub-second latency.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DebeziumConfigCdcConsumer {

    private final ObjectMapper objectMapper;
    private final GatewayConfigCacheService cacheService;

    @KafkaListener(
            topics = KafkaTopics.CDC_GATEWAY_CONFIG,
            groupId = "${spring.kafka.consumer.group-id:hyperllm-gateway-local}"
    )
    public void onCdcMessage(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode body = root.has("payload") && root.get("payload").isObject() ? root.get("payload") : root;
            String table = body.path("source").path("table").asText("");
            switch (table) {
                case "api_keys" -> {
                    String hash = firstText(body, "key_hash");
                    cacheService.evictKeyL1(hash);
                }
                case "projects", "organizations", "customer_tiers" -> cacheService.evictAllKeysL1();
                case "virtual_models" -> {
                    String modelCode = firstText(body, "model_code");
                    cacheService.evictRouteL1(modelCode);
                }
                case "channels", "providers", "model_channel_bindings", "pricing_rules" ->
                        cacheService.evictAllRoutesL1();
                default -> {
                    cacheService.evictAllKeysL1();
                    cacheService.evictAllRoutesL1();
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to parse CDC payload; evicting L1 caches conservatively", ex);
            cacheService.evictAllKeysL1();
            cacheService.evictAllRoutesL1();
        }
    }

    private static String firstText(JsonNode body, String field) {
        JsonNode after = body.path("after").get(field);
        if (after != null && after.isTextual() && !after.asText().isBlank()) {
            return after.asText();
        }
        JsonNode before = body.path("before").get(field);
        if (before != null && before.isTextual() && !before.asText().isBlank()) {
            return before.asText();
        }
        return null;
    }
}

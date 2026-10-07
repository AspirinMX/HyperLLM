package app.pagefault.hyperllm.gateway.billing;

import app.pagefault.hyperllm.common.messaging.BillingSettlementEvent;
import app.pagefault.hyperllm.common.messaging.KafkaTopics;
import app.pagefault.hyperllm.common.messaging.LlmRequestEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes asynchronous billing settlement and denormalized request telemetry events to Kafka.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayTelemetryService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void emitSettlementAndRequestEvent(
            BillingSettlementEvent settlement,
            LlmRequestEvent requestEvent
    ) {
        if (settlement != null && (settlement.cashDeltaMicros() != 0L || settlement.bonusDeltaMicros() != 0L)) {
            sendAsync(KafkaTopics.LLM_BILLING_SETTLEMENT, settlement.orgId().toString(), settlement);
        }
        if (requestEvent != null) {
            sendAsync(KafkaTopics.LLM_REQUEST_EVENTS, requestEvent.orgId().toString(), requestEvent);
        }
    }

    private void sendAsync(String topic, String key, Object event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, key, json).whenComplete((res, ex) -> {
                if (ex != null) {
                    log.warn("Failed to publish {} event to Kafka topic {}", key, topic, ex);
                }
            });
        } catch (Exception ex) {
            log.warn("Failed to serialize Kafka event for topic {}", topic, ex);
        }
    }
}

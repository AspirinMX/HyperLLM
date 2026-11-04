package app.pagefault.hyperllm.admin.analytics;

import app.pagefault.hyperllm.admin.analytics.ClickHouseAnalyticsStore.FactRow;
import app.pagefault.hyperllm.common.messaging.KafkaTopics;
import app.pagefault.hyperllm.common.messaging.LlmRequestEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Appends terminal request facts to ClickHouse and advances the committed position.
 *
 * <p>Validation happens here, before anything is persisted: identities, timestamps, numeric ranges
 * and string bounds are checked against the event contract. A record that fails is recorded with
 * its Kafka position and error class, and its offset still advances, so one poisonous payload
 * cannot block the whole partition. Only whitelisted fields leave this class; prompt, completion,
 * headers and upstream errors never reach the warehouse.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestEventConsumer {

    private static final int MAX_STRING = 512;

    private final ObjectMapper objectMapper;
    private final ClickHouseAnalyticsStore store;
    private final AnalyticsCursor cursor;

    @KafkaListener(
            topics = KafkaTopics.LLM_REQUEST_EVENTS,
            groupId = "${hyperllm.analytics.consumer-group:hyperllm-admin-requests}",
            containerFactory = "requestEventListenerContainerFactory",
            // A new report deployment starts from the retained facts, not from the next request.
            properties = {"auto.offset.reset=earliest"}
    )
    public void onBatch(List<ConsumerRecord<String, String>> records, Acknowledgment acknowledgment) {
        if (records.isEmpty()) {
            acknowledgment.acknowledge();
            return;
        }
        List<FactRow> facts = new ArrayList<>(records.size());
        List<AnalyticsCursor.Reject> rejects = new ArrayList<>();
        Map<Integer, Long> positions = new LinkedHashMap<>();
        Set<OffsetDateTime> hours = new LinkedHashSet<>();

        for (ConsumerRecord<String, String> record : records) {
            positions.merge(record.partition(), record.offset() + 1L, Math::max);
            try {
                LlmRequestEvent event = objectMapper.readValue(record.value(), LlmRequestEvent.class);
                validate(event);
                facts.add(new FactRow(event, record.partition(), record.offset()));
                hours.add(hourOf(event.createdAt()));
            } catch (Exception ex) {
                rejects.add(new AnalyticsCursor.Reject(
                        record.partition(), record.offset(), requestId(record.value()),
                        ex.getClass().getSimpleName(), abbreviate(ex.getMessage())));
            }
        }

        store.ensureSchema();
        store.insertFacts(facts);
        cursor.advance(positions, hours, rejects);
        acknowledgment.acknowledge();
        if (!rejects.isEmpty()) {
            log.warn("Rejected {} of {} request facts for the report", rejects.size(), records.size());
        }
    }

    private static void validate(LlmRequestEvent event) {
        if (event.createdAt() == null) {
            throw new IllegalArgumentException("createdAt is required");
        }
        if (event.orgId() == null || event.projectId() == null || event.channelId() == null) {
            throw new IllegalArgumentException("orgId, projectId and channelId are required");
        }
        if (isBlank(event.requestId())) {
            throw new IllegalArgumentException("requestId is required");
        }
        if (event.httpStatus() < 0 || event.httpStatus() > 599) {
            throw new IllegalArgumentException("httpStatus is out of range");
        }
        if (event.promptTokens() < 0 || event.completionTokens() < 0 || event.cachedReadTokens() < 0
                || event.cachedWriteTokens() < 0 || event.reasoningTokens() < 0) {
            throw new IllegalArgumentException("Token counts cannot be negative");
        }
        if (event.costMicros() < 0 || event.cashCostMicros() < 0 || event.bonusCostMicros() < 0
                || event.upstreamCostMicros() < 0) {
            throw new IllegalArgumentException("Amounts cannot be negative");
        }
        if (event.ttftMs() < 0 || event.durationMs() < 0) {
            throw new IllegalArgumentException("Latencies cannot be negative");
        }
        bounded(event.requestId(), "requestId");
        bounded(event.virtualModel(), "virtualModel");
        bounded(event.provider(), "provider");
        bounded(event.upstreamModel(), "upstreamModel");
        bounded(event.inboundProtocol(), "inboundProtocol");
        bounded(event.errorCode(), "errorCode");
        bounded(event.guardrailAction(), "guardrailAction");
        bounded(event.region(), "region");
        bounded(event.clientIpPrefix(), "clientIpPrefix");
    }

    private static void bounded(String value, String field) {
        if (value != null && value.length() > MAX_STRING) {
            throw new IllegalArgumentException(field + " exceeds " + MAX_STRING + " characters");
        }
    }

    private String requestId(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String id = node.path("requestId").asText(null);
            return id != null && id.length() <= 64 ? id : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static OffsetDateTime hourOf(OffsetDateTime at) {
        return at.toInstant().truncatedTo(ChronoUnit.HOURS).atOffset(ZoneOffset.UTC);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}

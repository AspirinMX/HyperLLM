package app.pagefault.hyperllm.domain.model.billing;

import app.pagefault.hyperllm.domain.model.enums.OutboxAggregateType;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Row of {@code outbox_events}. {@code aggregateType} holds the Debezium routing value
 * (see {@link OutboxAggregateType#topicSuffix()}), {@code aggregateId} is the Kafka partition key.
 */
public record OutboxEvent(
        UUID id,
        String aggregateType,
        String aggregateId,
        String type,
        String payload,
        OffsetDateTime createdAt
) {
}

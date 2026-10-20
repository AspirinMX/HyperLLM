package app.pagefault.hyperllm.common.messaging;

/**
 * Shared Kafka topic names matching {@code infra/kafka/create-topics.sh}.
 */
public final class KafkaTopics {

    public static final String CDC_GATEWAY_CONFIG = "cdc.gateway.config";
    /** Money events delivered from the outbox table by the outbox connector. */
    public static final String CDC_BILLING = "cdc.billing";

    public static final String LLM_BILLING_SETTLEMENT = "llm.billing.settlement";
    public static final String LLM_REQUEST_EVENTS = "llm.request.events";

    private KafkaTopics() {
    }
}

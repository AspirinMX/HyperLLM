package app.pagefault.hyperllm.domain.model.enums;

/**
 * Routing value stored in the Debezium EventRouter's {@code aggregatetype} column.
 */
public enum OutboxAggregateType {
    BILLING("billing");

    private final String topicSuffix;

    OutboxAggregateType(String topicSuffix) {
        this.topicSuffix = topicSuffix;
    }

    public String topicSuffix() {
        return topicSuffix;
    }
}

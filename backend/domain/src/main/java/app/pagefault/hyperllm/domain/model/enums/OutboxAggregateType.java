package app.pagefault.hyperllm.domain.model.enums;

/**
 * Debezium Outbox EventRouter routes on {@code aggregatetype}, so the persisted column value is a
 * topic suffix (for example {@code wallet.topup}) rather than the enum constant name.
 */
public enum OutboxAggregateType {
    WALLET_TOPUP("wallet.topup"),
    WALLET_ADJUST("wallet.adjust"),
    PACK_GRANT("pack.grant"),
    BONUS_GRANT("bonus.grant");

    private final String topicSuffix;

    OutboxAggregateType(String topicSuffix) {
        this.topicSuffix = topicSuffix;
    }

    public String topicSuffix() {
        return topicSuffix;
    }
}

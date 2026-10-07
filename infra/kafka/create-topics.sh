#!/usr/bin/env bash
set -euo pipefail

BOOTSTRAP="${KAFKA_INTERNAL_BOOTSTRAP:-kafka:29092}"
KAFKA_TOPICS_BIN="${KAFKA_TOPICS_BIN:-/opt/kafka/bin/kafka-topics.sh}"

create_topic() {
    local topic="$1"
    local partitions="$2"
    shift 2
    echo "Ensuring Kafka topic '${topic}' (partitions=${partitions})..."
    "${KAFKA_TOPICS_BIN}" \
        --bootstrap-server "${BOOTSTRAP}" \
        --create \
        --if-not-exists \
        --topic "${topic}" \
        --partitions "${partitions}" \
        --replication-factor 1 \
        "$@"
}

# Config CDC compacted stream consumed by every gateway instance to invalidate Caffeine L1
create_topic "cdc.gateway.config" 6 --config cleanup.policy=compact

# Outbox domain events (Phase 2 commercial billing & wallet grant sync)
create_topic "cdc.wallet.topup" 6 --config cleanup.policy=delete --config retention.ms=604800000
create_topic "cdc.wallet.adjust" 6 --config cleanup.policy=delete --config retention.ms=604800000
create_topic "cdc.pack.grant" 6 --config cleanup.policy=delete --config retention.ms=604800000
create_topic "cdc.bonus.grant" 6 --config cleanup.policy=delete --config retention.ms=604800000

# Gateway async telemetry & wallet ledger settlement streams
create_topic "llm.request.events" 12 --config cleanup.policy=delete --config retention.ms=604800000
create_topic "llm.billing.settlement" 12 --config cleanup.policy=delete --config retention.ms=604800000

echo "All HyperLLM Kafka topics are ready."

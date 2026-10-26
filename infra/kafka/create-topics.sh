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

# Money events, delivered from the outbox table by the outbox connector.
#
# One topic for every change that moves a balance, keyed by organisation: consumption and issuance
# for the same tenant have to stay in order, and splitting them by kind would put them on different
# partitions. The order of magnitude of partitions is the consumer parallelism, not a correctness knob.
create_topic "cdc.billing" 12 --config cleanup.policy=delete --config retention.ms=604800000

# Gateway request telemetry: usage metadata for the warehouse, carries no money and never goes
# through the outbox.
create_topic "llm.request.events" 12 --config cleanup.policy=delete --config retention.ms=604800000

# Retained only so an environment upgraded from the previous shape can drain it; the gateway no
# longer publishes here and no service consumes it.
create_topic "llm.billing.settlement" 12 --config cleanup.policy=delete --config retention.ms=604800000

echo "All HyperLLM Kafka topics are ready."

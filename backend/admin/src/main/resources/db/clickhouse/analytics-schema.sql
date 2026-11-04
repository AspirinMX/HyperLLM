-- Phase 2 (2.9): request facts and the versioned hourly rollup.
--
-- This file is the operator-facing copy; admin also ships the same statements as a classpath
-- resource so an existing Compose volume is upgraded on the next start. Keep the two in sync.
-- Every statement is idempotent and safe to run repeatedly.

CREATE DATABASE IF NOT EXISTS hyperllm;

CREATE TABLE IF NOT EXISTS hyperllm.llm_request_events (
    event_date            Date DEFAULT toDate(created_at),
    created_at            DateTime64(3, 'UTC'),
    request_id            String,
    trace_id              String,
    org_id                UUID,
    project_id            UUID,
    api_key_id            UUID,
    user_id               Nullable(UUID),
    virtual_model         LowCardinality(String),
    provider              LowCardinality(String),
    channel_id            UUID,
    upstream_model        LowCardinality(String),
    inbound_protocol      LowCardinality(String),
    is_stream             UInt8,
    http_status           UInt16,
    error_code            LowCardinality(String),
    retry_count           UInt8,
    is_sticky_hit         UInt8,
    guardrail_action      LowCardinality(String),
    is_debug_logged       UInt8,
    prompt_tokens         UInt32,
    cached_read_tokens    UInt32,
    cached_write_tokens   UInt32,
    completion_tokens     UInt32,
    reasoning_tokens      UInt32,
    cost_micros           Int64,
    cash_cost_micros      Int64,
    bonus_cost_micros     Int64,
    upstream_cost_micros  Int64,
    pack_deducted_units   Int64,
    ttft_ms               UInt32,
    tpot_ms               Float32,
    duration_ms           UInt32,
    client_ip_prefix      String,
    region                LowCardinality(String),
    -- Delivery position of this fact. It is the tie-breaker of duplicate identities and the
    -- boundary the rollup uses to include only what the consumer has committed.
    kafka_partition       UInt32 DEFAULT 0,
    kafka_offset          UInt64 DEFAULT 0,
    cost_known            UInt8 DEFAULT 1,
    ingested_at           DateTime64(3, 'UTC') DEFAULT now64(3)
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(event_date)
ORDER BY (org_id, project_id, event_date, created_at, request_id)
TTL event_date + INTERVAL 365 DAY DELETE;

-- Upgrading a volume created before 2.9 adds the delivery columns in place.
ALTER TABLE hyperllm.llm_request_events ADD COLUMN IF NOT EXISTS kafka_partition UInt32 DEFAULT 0;
ALTER TABLE hyperllm.llm_request_events ADD COLUMN IF NOT EXISTS kafka_offset UInt64 DEFAULT 0;
ALTER TABLE hyperllm.llm_request_events ADD COLUMN IF NOT EXISTS cost_known UInt8 DEFAULT 1;
ALTER TABLE hyperllm.llm_request_events ADD COLUMN IF NOT EXISTS ingested_at DateTime64(3, 'UTC') DEFAULT now64(3);

-- The old incremental view summed every inserted block, including re-deliveries. The rollup job
-- publishes a deduplicated version per hour instead; the view is stopped but its history is kept.
DROP VIEW IF EXISTS hyperllm.mv_usage_hourly_mv;

CREATE TABLE IF NOT EXISTS hyperllm.usage_hourly (
    hour_bucket               DateTime('UTC'),
    version                   UInt64,
    org_id                    UUID,
    project_id                UUID,
    api_key_id                UUID,
    virtual_model             LowCardinality(String),
    channel_id                UUID,
    request_count             UInt64,
    success_count             UInt64,
    sum_prompt_tokens         UInt64,
    sum_cached_read_tokens    UInt64,
    sum_cached_write_tokens   UInt64,
    sum_completion_tokens     UInt64,
    sum_reasoning_tokens      UInt64,
    sum_cost_micros           Int64,
    sum_cash_cost_micros      Int64,
    sum_bonus_cost_micros     Int64,
    sum_upstream_cost_micros  Int64,
    sum_pack_units            Int64,
    unknown_cost_requests     UInt64,
    ttft_state                AggregateFunction(quantilesTDigest(0.5, 0.9, 0.99), UInt32),
    duration_state            AggregateFunction(quantilesTDigest(0.5, 0.9, 0.99), UInt32)
) ENGINE = AggregatingMergeTree()
PARTITION BY toYYYYMM(hour_bucket)
ORDER BY (org_id, project_id, api_key_id, virtual_model, channel_id, hour_bucket, version);

-- Which version of an hour readers may use. A row is written only after the facts of that version
-- are fully inserted, so an interrupted rollup never becomes visible.
CREATE TABLE IF NOT EXISTS hyperllm.usage_hourly_published (
    hour_bucket  DateTime('UTC'),
    version      UInt64,
    published_at DateTime64(3, 'UTC') DEFAULT now64(3)
) ENGINE = ReplacingMergeTree(published_at)
ORDER BY hour_bucket;

CREATE TABLE IF NOT EXISTS hyperllm.llm_debug_payloads (
    created_at                 DateTime64(3, 'UTC'),
    org_id                     UUID,
    project_id                 UUID,
    request_id                 String,
    encryption_key_version     UInt16,
    encrypted_request_payload  String,
    encrypted_response_payload String,
    payload_bytes              UInt32,
    expires_at                 DateTime('UTC')
) ENGINE = MergeTree()
PARTITION BY toYYYYMMDD(created_at)
ORDER BY (org_id, project_id, created_at, request_id)
TTL expires_at DELETE;

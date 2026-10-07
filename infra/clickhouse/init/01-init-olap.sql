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
    region                LowCardinality(String)
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(event_date)
ORDER BY (org_id, project_id, event_date, created_at, request_id)
TTL event_date + INTERVAL 365 DAY DELETE;

CREATE TABLE IF NOT EXISTS hyperllm.mv_usage_hourly (
    hour_bucket              DateTime('UTC'),
    org_id                   UUID,
    project_id               UUID,
    api_key_id               UUID,
    virtual_model            LowCardinality(String),
    channel_id               UUID,
    request_count            SimpleAggregateFunction(sum, UInt64),
    success_count            SimpleAggregateFunction(sum, UInt64),
    sum_prompt_tokens        SimpleAggregateFunction(sum, UInt64),
    sum_cached_tokens        SimpleAggregateFunction(sum, UInt64),
    sum_completion_tokens    SimpleAggregateFunction(sum, UInt64),
    sum_cost_micros          SimpleAggregateFunction(sum, Int64),
    sum_cash_cost_micros     SimpleAggregateFunction(sum, Int64),
    sum_bonus_cost_micros    SimpleAggregateFunction(sum, Int64),
    sum_upstream_cost_micros SimpleAggregateFunction(sum, Int64),
    ttft_p95_state           AggregateFunction(quantile(0.95), UInt32)
) ENGINE = AggregatingMergeTree()
PARTITION BY toYYYYMM(hour_bucket)
ORDER BY (org_id, project_id, api_key_id, virtual_model, channel_id, hour_bucket);

CREATE MATERIALIZED VIEW IF NOT EXISTS hyperllm.mv_usage_hourly_mv TO hyperllm.mv_usage_hourly AS
SELECT
    toStartOfHour(created_at) AS hour_bucket,
    org_id,
    project_id,
    api_key_id,
    virtual_model,
    channel_id,
    toUInt64(1) AS request_count,
    toUInt64(http_status >= 200 AND http_status < 300) AS success_count,
    toUInt64(prompt_tokens) AS sum_prompt_tokens,
    toUInt64(cached_read_tokens) AS sum_cached_tokens,
    toUInt64(completion_tokens) AS sum_completion_tokens,
    cost_micros AS sum_cost_micros,
    cash_cost_micros AS sum_cash_cost_micros,
    bonus_cost_micros AS sum_bonus_cost_micros,
    upstream_cost_micros AS sum_upstream_cost_micros,
    quantileState(0.95)(ttft_ms) AS ttft_p95_state
FROM hyperllm.llm_request_events
GROUP BY org_id, project_id, api_key_id, virtual_model, channel_id, hour_bucket;

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

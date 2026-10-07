-- Phase 1 (Part 1): Providers, Channels, Virtual Models, Bindings & Versioned Pricing Rules

CREATE TABLE providers (
    id               UUID PRIMARY KEY,
    code             VARCHAR(32) NOT NULL UNIQUE,
    display_name     VARCHAR(80) NOT NULL,
    protocol_family  VARCHAR(32) NOT NULL
        CHECK (protocol_family IN ('OPENAI', 'ANTHROPIC', 'GEMINI', 'COHERE_RERANK')),
    default_base_url VARCHAR(512) NULL,
    auth_scheme      VARCHAR(32) NOT NULL
        CHECK (auth_scheme IN ('BEARER', 'X_API_KEY', 'AZURE_API_KEY', 'AWS_SIGV4', 'GCP_OAUTH')),
    status           VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO providers (id, code, display_name, protocol_family, default_base_url, auth_scheme)
VALUES
    ('01900000-0000-7000-8000-000000000101', 'OPENAI', 'OpenAI', 'OPENAI', 'https://api.openai.com/v1', 'BEARER'),
    ('01900000-0000-7000-8000-000000000102', 'AZURE_OPENAI', 'Azure OpenAI', 'OPENAI', NULL, 'AZURE_API_KEY'),
    ('01900000-0000-7000-8000-000000000103', 'ANTHROPIC', 'Anthropic', 'ANTHROPIC', 'https://api.anthropic.com', 'X_API_KEY'),
    ('01900000-0000-7000-8000-000000000104', 'AWS_BEDROCK', 'AWS Bedrock', 'ANTHROPIC', NULL, 'AWS_SIGV4'),
    ('01900000-0000-7000-8000-000000000105', 'GOOGLE_GEMINI', 'Google Gemini', 'GEMINI', 'https://generativelanguage.googleapis.com', 'X_API_KEY'),
    ('01900000-0000-7000-8000-000000000106', 'VERTEX_AI', 'Google Vertex AI', 'GEMINI', NULL, 'GCP_OAUTH'),
    ('01900000-0000-7000-8000-000000000107', 'VLLM', 'Self-Hosted vLLM', 'OPENAI', NULL, 'BEARER'),
    ('01900000-0000-7000-8000-000000000108', 'DEEPSEEK', 'DeepSeek', 'OPENAI', 'https://api.deepseek.com/v1', 'BEARER');

CREATE TABLE channels (
    id                        UUID PRIMARY KEY,
    provider_id               UUID NOT NULL REFERENCES providers (id) ON DELETE RESTRICT,
    name                      VARCHAR(120) NOT NULL,
    region                    VARCHAR(64) NULL,
    base_url_override         VARCHAR(512) NULL,
    encrypted_credentials     BYTEA NOT NULL,
    credential_key_version    SMALLINT NOT NULL DEFAULT 1,
    default_cost_discount_bps INTEGER NOT NULL DEFAULT 10000
        CHECK (default_cost_discount_bps >= 0),
    rpm_limit                 INTEGER NULL CHECK (rpm_limit IS NULL OR rpm_limit > 0),
    tpm_limit                 INTEGER NULL CHECK (tpm_limit IS NULL OR tpm_limit > 0),
    max_concurrency           INTEGER NULL CHECK (max_concurrency IS NULL OR max_concurrency > 0),
    health_check_model        VARCHAR(120) NULL,
    status                    VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED', 'CIRCUIT_OPEN', 'EXHAUSTED')),
    deleted_at                TIMESTAMPTZ NULL,
    version                   BIGINT NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_channels_provider_status
    ON channels (provider_id, status)
    WHERE deleted_at IS NULL;

CREATE TABLE virtual_models (
    id                   UUID PRIMARY KEY,
    model_code           VARCHAR(120) NOT NULL,
    display_name         VARCHAR(120) NOT NULL,
    provider_badge       VARCHAR(64) NULL,
    capability_type      VARCHAR(32) NOT NULL
        CHECK (capability_type IN ('CHAT', 'REASONING', 'EMBEDDING', 'IMAGE', 'AUDIO_TTS', 'AUDIO_STT', 'RERANK')),
    native_protocol      VARCHAR(32) NOT NULL
        CHECK (native_protocol IN ('OPENAI', 'ANTHROPIC', 'GEMINI', 'RERANK')),
    context_window       INTEGER NOT NULL CHECK (context_window > 0),
    max_output_tokens    INTEGER NULL CHECK (max_output_tokens IS NULL OR max_output_tokens > 0),
    supports_tools       BOOLEAN NOT NULL DEFAULT FALSE,
    supports_vision      BOOLEAN NOT NULL DEFAULT FALSE,
    supports_thinking    BOOLEAN NOT NULL DEFAULT FALSE,
    supports_prompt_cache BOOLEAN NOT NULL DEFAULT FALSE,
    routing_strategy     VARCHAR(32) NOT NULL DEFAULT 'PRIORITY_WEIGHTED'
        CHECK (routing_strategy IN ('PRIORITY_WEIGHTED', 'LOWEST_COST', 'TTFT_AWARE')),
    sticky_cache_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    sticky_ttl_seconds   INTEGER NOT NULL DEFAULT 300 CHECK (sticky_ttl_seconds > 0),
    visibility           VARCHAR(32) NOT NULL DEFAULT 'PUBLIC'
        CHECK (visibility IN ('PUBLIC', 'RESTRICTED')),
    fallback_model_code  VARCHAR(120) NULL,
    status               VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DEPRECATED', 'DISABLED')),
    metadata_json        JSONB NOT NULL DEFAULT '{}'::jsonb,
    deleted_at           TIMESTAMPTZ NULL,
    version              BIGINT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_virtual_models_code_active
    ON virtual_models (model_code)
    WHERE deleted_at IS NULL;

CREATE TABLE model_channel_bindings (
    id                             UUID PRIMARY KEY,
    virtual_model_id               UUID NOT NULL REFERENCES virtual_models (id) ON DELETE RESTRICT,
    channel_id                     UUID NOT NULL REFERENCES channels (id) ON DELETE RESTRICT,
    upstream_model_name            VARCHAR(160) NOT NULL,
    priority_tier                  SMALLINT NOT NULL DEFAULT 1 CHECK (priority_tier >= 1),
    weight                         INTEGER NOT NULL DEFAULT 100 CHECK (weight > 0),
    cost_discount_bps_override     INTEGER NULL
        CHECK (cost_discount_bps_override IS NULL OR cost_discount_bps_override >= 0),
    upstream_pricing_override_json JSONB NULL,
    extra_headers_json             JSONB NOT NULL DEFAULT '{}'::jsonb,
    status                         VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    deleted_at                     TIMESTAMPTZ NULL,
    version                        BIGINT NOT NULL DEFAULT 0,
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_mcb_model_channel_active
    ON model_channel_bindings (virtual_model_id, channel_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_mcb_model_tier_active
    ON model_channel_bindings (virtual_model_id, priority_tier)
    WHERE deleted_at IS NULL AND status = 'ACTIVE';

CREATE TABLE pricing_rules (
    id                         UUID PRIMARY KEY,
    virtual_model_id           UUID NOT NULL REFERENCES virtual_models (id) ON DELETE RESTRICT,
    billing_mode               VARCHAR(32) NOT NULL
        CHECK (billing_mode IN ('TOKEN', 'PER_CALL', 'IMAGE', 'AUDIO_CHAR', 'AUDIO_DURATION')),
    input_price_micros_1m      BIGINT NOT NULL DEFAULT 0 CHECK (input_price_micros_1m >= 0),
    output_price_micros_1m     BIGINT NOT NULL DEFAULT 0 CHECK (output_price_micros_1m >= 0),
    cache_read_price_micros_1m BIGINT NOT NULL DEFAULT 0 CHECK (cache_read_price_micros_1m >= 0),
    cache_write_price_micros_1m BIGINT NOT NULL DEFAULT 0 CHECK (cache_write_price_micros_1m >= 0),
    reasoning_price_micros_1m  BIGINT NOT NULL DEFAULT 0 CHECK (reasoning_price_micros_1m >= 0),
    per_call_micros            BIGINT NOT NULL DEFAULT 0 CHECK (per_call_micros >= 0),
    tiered_rules_json          JSONB NOT NULL DEFAULT '[]'::jsonb,
    modality_rules_json        JSONB NOT NULL DEFAULT '{}'::jsonb,
    effective_from             TIMESTAMPTZ NOT NULL,
    effective_to               TIMESTAMPTZ NULL,
    created_by_admin_id        UUID NULL REFERENCES admin_users (id) ON DELETE RESTRICT,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_pricing_effective_window CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE INDEX idx_pricing_rules_model_effective
    ON pricing_rules (virtual_model_id, effective_from DESC);

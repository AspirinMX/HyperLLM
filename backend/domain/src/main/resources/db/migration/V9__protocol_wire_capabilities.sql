-- Phase 3 (Part 1): Wire Protocol Capabilities, Gemini Auth Scheme & Cross-Protocol Translation Policy

ALTER TABLE providers
    DROP CONSTRAINT IF EXISTS providers_auth_scheme_check;

ALTER TABLE providers
    ADD CONSTRAINT providers_auth_scheme_check
        CHECK (auth_scheme IN ('BEARER', 'X_API_KEY', 'X_GOOG_API_KEY', 'AZURE_API_KEY', 'AWS_SIGV4', 'GCP_OAUTH'));

ALTER TABLE providers
    ADD COLUMN supported_protocols   VARCHAR(32)[] NOT NULL DEFAULT ARRAY['OPENAI_CHAT']::varchar(32)[],
    ADD COLUMN default_wire_protocol VARCHAR(32)   NOT NULL DEFAULT 'OPENAI_CHAT'
        CHECK (default_wire_protocol IN ('OPENAI_CHAT', 'OPENAI_RESPONSES', 'ANTHROPIC_MESSAGES', 'GEMINI_GENERATE_CONTENT')),
    ADD CONSTRAINT chk_providers_supported_protocols
        CHECK (cardinality(supported_protocols) > 0
            AND supported_protocols <@ ARRAY['OPENAI_CHAT', 'OPENAI_RESPONSES', 'ANTHROPIC_MESSAGES', 'GEMINI_GENERATE_CONTENT']::varchar(32)[]);

UPDATE providers
SET supported_protocols = ARRAY['OPENAI_CHAT']::varchar(32)[],
    default_wire_protocol = 'OPENAI_CHAT'
WHERE protocol_family = 'OPENAI';

UPDATE providers
SET supported_protocols = ARRAY['OPENAI_CHAT', 'OPENAI_RESPONSES']::varchar(32)[],
    default_wire_protocol = 'OPENAI_CHAT'
WHERE code IN ('OPENAI', 'AZURE_OPENAI');

UPDATE providers
SET supported_protocols = ARRAY['ANTHROPIC_MESSAGES']::varchar(32)[],
    default_wire_protocol = 'ANTHROPIC_MESSAGES'
WHERE protocol_family = 'ANTHROPIC';

UPDATE providers
SET supported_protocols = ARRAY['GEMINI_GENERATE_CONTENT']::varchar(32)[],
    default_wire_protocol = 'GEMINI_GENERATE_CONTENT',
    auth_scheme = CASE WHEN code = 'GOOGLE_GEMINI' THEN 'X_GOOG_API_KEY' ELSE auth_scheme END
WHERE protocol_family = 'GEMINI';

ALTER TABLE model_channel_bindings
    ADD COLUMN upstream_protocol VARCHAR(32) NULL
        CHECK (upstream_protocol IS NULL OR upstream_protocol IN ('OPENAI_CHAT', 'OPENAI_RESPONSES', 'ANTHROPIC_MESSAGES', 'GEMINI_GENERATE_CONTENT'));

ALTER TABLE virtual_models
    ADD COLUMN allow_protocol_translation BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN translation_policy         VARCHAR(16) NOT NULL DEFAULT 'BEST_EFFORT'
        CHECK (translation_policy IN ('BEST_EFFORT', 'STRICT'));

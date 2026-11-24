package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"flag"
	"fmt"
	"os"
	"strings"
)

const (
	standardTierID = "01900000-0000-7000-8000-000000000001"
	openaiProvID   = "01900000-0000-7000-8000-000000000101"
	anthropicProv  = "01900000-0000-7000-8000-000000000103"
	geminiProvID   = "01900000-0000-7000-8000-000000000105"

	benchUserID    = "01900000-0000-7000-8000-00000000b001"
	benchOrgID     = "01900000-0000-7000-8000-00000000b002"
	benchOrgMemID  = "01900000-0000-7000-8000-00000000b003"
	benchProjectID = "01900000-0000-7000-8000-00000000b004"
	benchKeyID     = "01900000-0000-7000-8000-00000000b005"

	chOpenAIID    = "01900000-0000-7000-8000-00000000b101"
	chAnthropicID = "01900000-0000-7000-8000-00000000b102"
	chGeminiID    = "01900000-0000-7000-8000-00000000b103"

	vmOpenAIChatID = "01900000-0000-7000-8000-00000000b201"
	vmOpenAIRespID = "01900000-0000-7000-8000-00000000b202"
	vmAnthropicID  = "01900000-0000-7000-8000-00000000b203"
	vmGeminiID     = "01900000-0000-7000-8000-00000000b204"

	bindOpenAIChatID = "01900000-0000-7000-8000-00000000b301"
	bindOpenAIRespID = "01900000-0000-7000-8000-00000000b302"
	bindAnthropicID  = "01900000-0000-7000-8000-00000000b303"
	bindGeminiID     = "01900000-0000-7000-8000-00000000b304"

	priceOpenAIChatID = "01900000-0000-7000-8000-00000000b401"
	priceOpenAIRespID = "01900000-0000-7000-8000-00000000b402"
	priceAnthropicID  = "01900000-0000-7000-8000-00000000b403"
	priceGeminiID     = "01900000-0000-7000-8000-00000000b404"
)

func main() {
	defaultMasterKey := os.Getenv("HYPERLLM_AES_MASTER_KEY")
	if defaultMasterKey == "" {
		defaultMasterKey = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
	}
	defaultPepper := os.Getenv("HYPERLLM_KEY_PEPPER_V1")
	if defaultPepper == "" {
		defaultPepper = "dev-only-hyperllm-key-pepper-v1-change-in-production"
	}

	aesKeyB64 := flag.String("aes-key", defaultMasterKey, "Base64-encoded 32-byte AES-256-GCM master key")
	pepper := flag.String("pepper", defaultPepper, "HMAC-SHA256 API key pepper v1")
	rawAPIKey := flag.String("api-key", "sk-bench0010000000000000000000000000000000000000001", "Live client API key (51 chars)")
	upstreamURL := flag.String("upstream-url", "http://127.0.0.1:18080", "Mock upstream server base URL")
	walletUSD := flag.Int64("wallet-usd", 100000000, "Initial cash balance in USD (1 USD = 1_000_000 micros)")
	maxOrgConc := flag.Int("max-org-concurrency", 100000, "Organization max_concurrency limit")
	flag.Parse()

	keyBytes, err := base64.StdEncoding.DecodeString(strings.TrimSpace(*aesKeyB64))
	if err != nil || len(keyBytes) != 32 {
		fmt.Fprintf(os.Stderr, "invalid AES master key (expected Base64 32 bytes): %v\n", err)
		os.Exit(1)
	}

	mac := hmac.New(sha256.New, []byte(*pepper))
	mac.Write([]byte(*rawAPIKey))
	keyHashHex := hex.EncodeToString(mac.Sum(nil))
	keyPrefix := (*rawAPIKey)[:11] + "..." + (*rawAPIKey)[len(*rawAPIKey)-4:]

	encOpenAI := encryptEnvelopeHex(keyBytes, `{"apiKey":"sk-mock-openai"}`, chOpenAIID)
	encAnthropic := encryptEnvelopeHex(keyBytes, `{"apiKey":"sk-mock-anthropic"}`, chAnthropicID)
	encGemini := encryptEnvelopeHex(keyBytes, `{"apiKey":"sk-mock-gemini"}`, chGeminiID)

	base := strings.TrimRight(*upstreamURL, "/")
	walletMicros := (*walletUSD) * 1_000_000

	fmt.Printf(`-- HyperLLM idempotent benchmark seed data
-- Client API Key: %s
-- Upstream Base:  %s
BEGIN;

INSERT INTO users (id, email, email_verified_at, display_name, default_org_id, status)
VALUES ('%s', 'bench@hyperllm.local', now(), 'Benchmark User', '%s', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO organizations (id, name, slug, owner_user_id, tier_id, discount_ratio_bps, max_concurrency, is_personal, status)
VALUES ('%s', 'Benchmark Org', 'bench-org', '%s', '%s', 10000, %d, FALSE, 'ACTIVE')
ON CONFLICT (id) DO UPDATE SET
    max_concurrency = EXCLUDED.max_concurrency,
    status = 'ACTIVE',
    deleted_at = NULL;

INSERT INTO org_members (id, org_id, user_id, role)
VALUES ('%s', '%s', '%s', 'OWNER')
ON CONFLICT (org_id, user_id) DO NOTHING;

INSERT INTO projects (id, org_id, name, environment, monthly_hard_cap_micros, monthly_soft_cap_micros, allowed_models, rpm_limit, tpm_limit, max_concurrency, status)
VALUES ('%s', '%s', 'bench-prod', 'PRODUCTION', NULL, NULL, NULL, NULL, NULL, NULL, 'ACTIVE')
ON CONFLICT (id) DO UPDATE SET
    rpm_limit = NULL,
    tpm_limit = NULL,
    max_concurrency = NULL,
    allowed_models = NULL,
    status = 'ACTIVE',
    deleted_at = NULL;

INSERT INTO wallets (org_id, cash_balance_micros, bonus_balance_micros, total_recharged_micros)
VALUES ('%s', %d, 0, %d)
ON CONFLICT (org_id) DO UPDATE SET
    cash_balance_micros = EXCLUDED.cash_balance_micros,
    total_recharged_micros = GREATEST(wallets.total_recharged_micros, EXCLUDED.total_recharged_micros),
    updated_at = now();

INSERT INTO api_keys (id, project_id, org_id, created_by_user_id, name, key_prefix, key_hash, pepper_version, allowed_models, ip_whitelist, rpm_limit, tpm_limit, max_concurrency, status)
VALUES ('%s', '%s', '%s', '%s', 'Benchmark Live Key', '%s', '%s', 1, NULL, NULL, NULL, NULL, NULL, 'ACTIVE')
ON CONFLICT (id) DO UPDATE SET
    key_prefix = EXCLUDED.key_prefix,
    key_hash = EXCLUDED.key_hash,
    rpm_limit = NULL,
    tpm_limit = NULL,
    max_concurrency = NULL,
    allowed_models = NULL,
    ip_whitelist = NULL,
    status = 'ACTIVE',
    deleted_at = NULL;

INSERT INTO channels (id, provider_id, name, region, base_url_override, encrypted_credentials, credential_key_version, default_cost_discount_bps, rpm_limit, tpm_limit, max_concurrency, status)
VALUES
    ('%s', '%s', 'bench-openai-ch', 'helsinki', '%s/v1', decode('%s', 'hex'), 1, 10000, NULL, NULL, NULL, 'ACTIVE'),
    ('%s', '%s', 'bench-anthropic-ch', 'helsinki', '%s', decode('%s', 'hex'), 1, 10000, NULL, NULL, NULL, 'ACTIVE'),
    ('%s', '%s', 'bench-gemini-ch', 'helsinki', '%s', decode('%s', 'hex'), 1, 10000, NULL, NULL, NULL, 'ACTIVE')
ON CONFLICT (id) DO UPDATE SET
    base_url_override = EXCLUDED.base_url_override,
    encrypted_credentials = EXCLUDED.encrypted_credentials,
    rpm_limit = NULL,
    tpm_limit = NULL,
    max_concurrency = NULL,
    status = 'ACTIVE',
    deleted_at = NULL;

INSERT INTO virtual_models (
    id, model_code, display_name, provider_badge, capability_type, native_protocol,
    context_window, max_output_tokens, supports_tools, supports_vision, supports_thinking,
    supports_prompt_cache, routing_strategy, visibility, status, allow_protocol_translation, translation_policy
)
VALUES
    ('%s', 'bench-openai-chat', 'Bench OpenAI Chat', 'OpenAI', 'CHAT', 'OPENAI', 200000, 16384, TRUE, TRUE, TRUE, TRUE, 'PRIORITY_WEIGHTED', 'PUBLIC', 'ACTIVE', TRUE, 'BEST_EFFORT'),
    ('%s', 'bench-openai-responses', 'Bench OpenAI Responses', 'OpenAI', 'CHAT', 'OPENAI', 200000, 16384, TRUE, TRUE, TRUE, TRUE, 'PRIORITY_WEIGHTED', 'PUBLIC', 'ACTIVE', TRUE, 'BEST_EFFORT'),
    ('%s', 'bench-anthropic', 'Bench Anthropic Messages', 'Anthropic', 'CHAT', 'ANTHROPIC', 200000, 16384, TRUE, TRUE, TRUE, TRUE, 'PRIORITY_WEIGHTED', 'PUBLIC', 'ACTIVE', TRUE, 'BEST_EFFORT'),
    ('%s', 'bench-gemini', 'Bench Google Gemini', 'Google', 'CHAT', 'GEMINI', 200000, 16384, TRUE, TRUE, TRUE, TRUE, 'PRIORITY_WEIGHTED', 'PUBLIC', 'ACTIVE', TRUE, 'BEST_EFFORT')
ON CONFLICT (id) DO UPDATE SET
    status = 'ACTIVE',
    allow_protocol_translation = TRUE,
    deleted_at = NULL;

INSERT INTO model_channel_bindings (
    id, virtual_model_id, channel_id, upstream_model_name, upstream_protocol, priority_tier, weight, extra_headers_json, status
)
VALUES
    ('%s', '%s', '%s', 'gpt-bench', 'OPENAI_CHAT', 1, 100, '{}'::jsonb, 'ACTIVE'),
    ('%s', '%s', '%s', 'gpt-bench-resp', 'OPENAI_RESPONSES', 1, 100, '{}'::jsonb, 'ACTIVE'),
    ('%s', '%s', '%s', 'claude-bench', 'ANTHROPIC_MESSAGES', 1, 100, '{}'::jsonb, 'ACTIVE'),
    ('%s', '%s', '%s', 'gemini-bench', 'GEMINI_GENERATE_CONTENT', 1, 100, '{}'::jsonb, 'ACTIVE')
ON CONFLICT (id) DO UPDATE SET
    upstream_protocol = EXCLUDED.upstream_protocol,
    status = 'ACTIVE',
    deleted_at = NULL;

INSERT INTO pricing_rules (
    id, virtual_model_id, billing_mode, input_price_micros_1m, output_price_micros_1m,
    cache_read_price_micros_1m, cache_write_price_micros_1m, reasoning_price_micros_1m,
    per_call_micros, effective_from
)
VALUES
    ('%s', '%s', 'TOKEN', 1000000, 2000000, 100000, 1250000, 2000000, 0, now() - interval '1 day'),
    ('%s', '%s', 'TOKEN', 1000000, 2000000, 100000, 1250000, 2000000, 0, now() - interval '1 day'),
    ('%s', '%s', 'TOKEN', 1000000, 2000000, 100000, 1250000, 2000000, 0, now() - interval '1 day'),
    ('%s', '%s', 'TOKEN', 1000000, 2000000, 100000, 1250000, 2000000, 0, now() - interval '1 day')
ON CONFLICT (id) DO NOTHING;

COMMIT;
`,
		*rawAPIKey, base,
		benchUserID, benchOrgID,
		benchOrgID, benchUserID, standardTierID, *maxOrgConc,
		benchOrgMemID, benchOrgID, benchUserID,
		benchProjectID, benchOrgID,
		benchOrgID, walletMicros, walletMicros,
		benchKeyID, benchProjectID, benchOrgID, benchUserID, keyPrefix, keyHashHex,
		chOpenAIID, openaiProvID, base, encOpenAI,
		chAnthropicID, anthropicProv, base, encAnthropic,
		chGeminiID, geminiProvID, base, encGemini,
		vmOpenAIChatID, vmOpenAIRespID, vmAnthropicID, vmGeminiID,
		bindOpenAIChatID, vmOpenAIChatID, chOpenAIID,
		bindOpenAIRespID, vmOpenAIRespID, chOpenAIID,
		bindAnthropicID, vmAnthropicID, chAnthropicID,
		bindGeminiID, vmGeminiID, chGeminiID,
		priceOpenAIChatID, vmOpenAIChatID,
		priceOpenAIRespID, vmOpenAIRespID,
		priceAnthropicID, vmAnthropicID,
		priceGeminiID, vmGeminiID,
	)
}

// encryptEnvelopeHex matches app.pagefault.hyperllm.common.crypto.AesGcmCipher binary layout:
// [1B key_version=0x01][12B random IV][AES-256-GCM ciphertext + 16B tag] with AAD = channelId UTF-8 bytes.
func encryptEnvelopeHex(key []byte, plaintext string, aad string) string {
	block, err := aes.NewCipher(key)
	if err != nil {
		panic(err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		panic(err)
	}
	iv := make([]byte, 12)
	if _, err := rand.Read(iv); err != nil {
		panic(err)
	}
	ct := gcm.Seal(nil, iv, []byte(plaintext), []byte(aad))
	env := make([]byte, 0, 1+len(iv)+len(ct))
	env = append(env, 0x01)
	env = append(env, iv...)
	env = append(env, ct...)
	return hex.EncodeToString(env)
}

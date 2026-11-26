//! Idempotent SQL seed generator for HyperLLM benchmark tenants, wallets, API keys, channels,
//! virtual models, bindings, and pricing rules.
//!
//! Drop-in Rust replacement for `benchmark/cmd/bench-seed/main.go`. Produces byte-compatible
//! `AesGcmCipher` credential envelopes (`[0x01][12B IV][AES-256-GCM ciphertext + 16B tag]` with
//! channel UUID UTF-8 bytes as AAD) and HMAC-SHA256 API key hashes.

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use base64::Engine;
use hmac::{Hmac, Mac};
use hyperllm_bench::Flags;
use rand::RngCore;
use sha2::Sha256;

const STANDARD_TIER_ID: &str = "01900000-0000-7000-8000-000000000001";
const OPENAI_PROV_ID: &str = "01900000-0000-7000-8000-000000000101";
const ANTHROPIC_PROV_ID: &str = "01900000-0000-7000-8000-000000000103";
const GEMINI_PROV_ID: &str = "01900000-0000-7000-8000-000000000105";

const BENCH_USER_ID: &str = "01900000-0000-7000-8000-00000000b001";
const BENCH_ORG_ID: &str = "01900000-0000-7000-8000-00000000b002";
const BENCH_ORG_MEM_ID: &str = "01900000-0000-7000-8000-00000000b003";
const BENCH_PROJECT_ID: &str = "01900000-0000-7000-8000-00000000b004";
const BENCH_KEY_ID: &str = "01900000-0000-7000-8000-00000000b005";

const CH_OPENAI_ID: &str = "01900000-0000-7000-8000-00000000b101";
const CH_ANTHROPIC_ID: &str = "01900000-0000-7000-8000-00000000b102";
const CH_GEMINI_ID: &str = "01900000-0000-7000-8000-00000000b103";

const VM_OPENAI_CHAT_ID: &str = "01900000-0000-7000-8000-00000000b201";
const VM_OPENAI_RESP_ID: &str = "01900000-0000-7000-8000-00000000b202";
const VM_ANTHROPIC_ID: &str = "01900000-0000-7000-8000-00000000b203";
const VM_GEMINI_ID: &str = "01900000-0000-7000-8000-00000000b204";

const BIND_OPENAI_CHAT_ID: &str = "01900000-0000-7000-8000-00000000b301";
const BIND_OPENAI_RESP_ID: &str = "01900000-0000-7000-8000-00000000b302";
const BIND_ANTHROPIC_ID: &str = "01900000-0000-7000-8000-00000000b303";
const BIND_GEMINI_ID: &str = "01900000-0000-7000-8000-00000000b304";

const PRICE_OPENAI_CHAT_ID: &str = "01900000-0000-7000-8000-00000000b401";
const PRICE_OPENAI_RESP_ID: &str = "01900000-0000-7000-8000-00000000b402";
const PRICE_ANTHROPIC_ID: &str = "01900000-0000-7000-8000-00000000b403";
const PRICE_GEMINI_ID: &str = "01900000-0000-7000-8000-00000000b404";

type HmacSha256 = Hmac<Sha256>;

fn hmac_sha256_hex(pepper: &str, api_key: &str) -> String {
    let mut mac = <HmacSha256 as Mac>::new_from_slice(pepper.as_bytes())
        .expect("hmac accepts any key len");
    mac.update(api_key.as_bytes());
    hex::encode(mac.finalize().into_bytes())
}

/// Matches `app.pagefault.hyperllm.common.crypto.AesGcmCipher` binary layout:
/// `[1B key_version=0x01][12B random IV][AES-256-GCM ciphertext + 16B tag]` with AAD = channelId UTF-8.
fn encrypt_envelope_hex(key: &[u8], plaintext: &str, aad: &str) -> String {
    let cipher = Aes256Gcm::new_from_slice(key).expect("32-byte AES-256 key");
    let mut iv = [0u8; 12];
    rand::thread_rng().fill_bytes(&mut iv);
    let nonce = Nonce::from_slice(&iv);
    let ct = cipher
        .encrypt(
            nonce,
            Payload {
                msg: plaintext.as_bytes(),
                aad: aad.as_bytes(),
            },
        )
        .expect("AES-256-GCM encrypt");
    let mut env = Vec::with_capacity(1 + iv.len() + ct.len());
    env.push(0x01);
    env.extend_from_slice(&iv);
    env.extend_from_slice(&ct);
    hex::encode(env)
}

fn main() {
    let flags = match Flags::parse(std::env::args()) {
        Ok(f) => f,
        Err(msg) if msg == "help" => {
            eprintln!("Usage: bench-seed [-aes-key BASE64] [-pepper STR] [-api-key KEY] [-upstream-url URL] [-wallet-usd 100000000] [-max-org-concurrency 100000]");
            std::process::exit(0);
        }
        Err(e) => {
            eprintln!("ERROR: {e}");
            std::process::exit(2);
        }
    };

    let default_master_key = std::env::var("HYPERLLM_AES_MASTER_KEY")
        .ok()
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=".to_string());
    let default_pepper = std::env::var("HYPERLLM_KEY_PEPPER_V1")
        .ok()
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "dev-only-hyperllm-key-pepper-v1-change-in-production".to_string());

    let aes_key_b64 = flags.get_str("aes-key", &default_master_key);
    let pepper = flags.get_str("pepper", &default_pepper);
    let raw_api_key = flags.get_str(
        "api-key",
        "sk-bench0010000000000000000000000000000000000000001",
    );
    let upstream_url = flags.get_str("upstream-url", "http://127.0.0.1:18080");
    let wallet_usd = flags.get_i64("wallet-usd", 100_000_000).unwrap();
    let max_org_conc = flags.get_i64("max-org-concurrency", 100_000).unwrap();

    let key_bytes = base64::engine::general_purpose::STANDARD
        .decode(aes_key_b64.trim())
        .unwrap_or_else(|e| {
            eprintln!("invalid AES master key (expected Base64 32 bytes): {e}");
            std::process::exit(1);
        });
    if key_bytes.len() != 32 {
        eprintln!(
            "invalid AES master key length: got {} bytes, expected 32",
            key_bytes.len()
        );
        std::process::exit(1);
    }

    let key_hash_hex = hmac_sha256_hex(&pepper, &raw_api_key);
    let key_prefix = format!(
        "{}...{}",
        &raw_api_key[..11.min(raw_api_key.len())],
        &raw_api_key[raw_api_key.len().saturating_sub(4)..]
    );

    let enc_openai = encrypt_envelope_hex(&key_bytes, r#"{"apiKey":"sk-mock-openai"}"#, CH_OPENAI_ID);
    let enc_anthropic =
        encrypt_envelope_hex(&key_bytes, r#"{"apiKey":"sk-mock-anthropic"}"#, CH_ANTHROPIC_ID);
    let enc_gemini = encrypt_envelope_hex(&key_bytes, r#"{"apiKey":"sk-mock-gemini"}"#, CH_GEMINI_ID);

    let base = upstream_url.trim_end_matches('/');
    let wallet_micros = wallet_usd * 1_000_000;

    print!(
        r#"-- HyperLLM idempotent benchmark seed data
-- Client API Key: {raw_api_key}
-- Upstream Base:  {base}
BEGIN;

INSERT INTO users (id, email, email_verified_at, display_name, default_org_id, status)
VALUES ('{BENCH_USER_ID}', 'bench@hyperllm.local', now(), 'Benchmark User', '{BENCH_ORG_ID}', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO organizations (id, name, slug, owner_user_id, tier_id, discount_ratio_bps, max_concurrency, is_personal, status)
VALUES ('{BENCH_ORG_ID}', 'Benchmark Org', 'bench-org', '{BENCH_USER_ID}', '{STANDARD_TIER_ID}', 10000, {max_org_conc}, FALSE, 'ACTIVE')
ON CONFLICT (id) DO UPDATE SET
    max_concurrency = EXCLUDED.max_concurrency,
    status = 'ACTIVE',
    deleted_at = NULL;

INSERT INTO org_members (id, org_id, user_id, role)
VALUES ('{BENCH_ORG_MEM_ID}', '{BENCH_ORG_ID}', '{BENCH_USER_ID}', 'OWNER')
ON CONFLICT (org_id, user_id) DO NOTHING;

INSERT INTO projects (id, org_id, name, environment, monthly_hard_cap_micros, monthly_soft_cap_micros, allowed_models, rpm_limit, tpm_limit, max_concurrency, status)
VALUES ('{BENCH_PROJECT_ID}', '{BENCH_ORG_ID}', 'bench-prod', 'PRODUCTION', NULL, NULL, NULL, NULL, NULL, NULL, 'ACTIVE')
ON CONFLICT (id) DO UPDATE SET
    rpm_limit = NULL,
    tpm_limit = NULL,
    max_concurrency = NULL,
    allowed_models = NULL,
    status = 'ACTIVE',
    deleted_at = NULL;

INSERT INTO wallets (org_id, cash_balance_micros, bonus_balance_micros, total_recharged_micros)
VALUES ('{BENCH_ORG_ID}', {wallet_micros}, 0, {wallet_micros})
ON CONFLICT (org_id) DO UPDATE SET
    cash_balance_micros = EXCLUDED.cash_balance_micros,
    total_recharged_micros = GREATEST(wallets.total_recharged_micros, EXCLUDED.total_recharged_micros),
    updated_at = now();

INSERT INTO api_keys (id, project_id, org_id, created_by_user_id, name, key_prefix, key_hash, pepper_version, allowed_models, ip_whitelist, rpm_limit, tpm_limit, max_concurrency, status)
VALUES ('{BENCH_KEY_ID}', '{BENCH_PROJECT_ID}', '{BENCH_ORG_ID}', '{BENCH_USER_ID}', 'Benchmark Live Key', '{key_prefix}', '{key_hash_hex}', 1, NULL, NULL, NULL, NULL, NULL, 'ACTIVE')
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
    ('{CH_OPENAI_ID}', '{OPENAI_PROV_ID}', 'bench-openai-ch', 'helsinki', '{base}/v1', decode('{enc_openai}', 'hex'), 1, 10000, NULL, NULL, NULL, 'ACTIVE'),
    ('{CH_ANTHROPIC_ID}', '{ANTHROPIC_PROV_ID}', 'bench-anthropic-ch', 'helsinki', '{base}', decode('{enc_anthropic}', 'hex'), 1, 10000, NULL, NULL, NULL, 'ACTIVE'),
    ('{CH_GEMINI_ID}', '{GEMINI_PROV_ID}', 'bench-gemini-ch', 'helsinki', '{base}', decode('{enc_gemini}', 'hex'), 1, 10000, NULL, NULL, NULL, 'ACTIVE')
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
    ('{VM_OPENAI_CHAT_ID}', 'bench-openai-chat', 'Bench OpenAI Chat', 'OpenAI', 'CHAT', 'OPENAI', 200000, 16384, TRUE, TRUE, TRUE, TRUE, 'PRIORITY_WEIGHTED', 'PUBLIC', 'ACTIVE', TRUE, 'BEST_EFFORT'),
    ('{VM_OPENAI_RESP_ID}', 'bench-openai-responses', 'Bench OpenAI Responses', 'OpenAI', 'CHAT', 'OPENAI', 200000, 16384, TRUE, TRUE, TRUE, TRUE, 'PRIORITY_WEIGHTED', 'PUBLIC', 'ACTIVE', TRUE, 'BEST_EFFORT'),
    ('{VM_ANTHROPIC_ID}', 'bench-anthropic', 'Bench Anthropic Messages', 'Anthropic', 'CHAT', 'ANTHROPIC', 200000, 16384, TRUE, TRUE, TRUE, TRUE, 'PRIORITY_WEIGHTED', 'PUBLIC', 'ACTIVE', TRUE, 'BEST_EFFORT'),
    ('{VM_GEMINI_ID}', 'bench-gemini', 'Bench Google Gemini', 'Google', 'CHAT', 'GEMINI', 200000, 16384, TRUE, TRUE, TRUE, TRUE, 'PRIORITY_WEIGHTED', 'PUBLIC', 'ACTIVE', TRUE, 'BEST_EFFORT')
ON CONFLICT (id) DO UPDATE SET
    status = 'ACTIVE',
    allow_protocol_translation = TRUE,
    deleted_at = NULL;

INSERT INTO model_channel_bindings (
    id, virtual_model_id, channel_id, upstream_model_name, upstream_protocol, priority_tier, weight, extra_headers_json, status
)
VALUES
    ('{BIND_OPENAI_CHAT_ID}', '{VM_OPENAI_CHAT_ID}', '{CH_OPENAI_ID}', 'gpt-bench', 'OPENAI_CHAT', 1, 100, '{{}}'::jsonb, 'ACTIVE'),
    ('{BIND_OPENAI_RESP_ID}', '{VM_OPENAI_RESP_ID}', '{CH_OPENAI_ID}', 'gpt-bench-resp', 'OPENAI_RESPONSES', 1, 100, '{{}}'::jsonb, 'ACTIVE'),
    ('{BIND_ANTHROPIC_ID}', '{VM_ANTHROPIC_ID}', '{CH_ANTHROPIC_ID}', 'claude-bench', 'ANTHROPIC_MESSAGES', 1, 100, '{{}}'::jsonb, 'ACTIVE'),
    ('{BIND_GEMINI_ID}', '{VM_GEMINI_ID}', '{CH_GEMINI_ID}', 'gemini-bench', 'GEMINI_GENERATE_CONTENT', 1, 100, '{{}}'::jsonb, 'ACTIVE')
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
    ('{PRICE_OPENAI_CHAT_ID}', '{VM_OPENAI_CHAT_ID}', 'TOKEN', 1000000, 2000000, 100000, 1250000, 2000000, 0, now() - interval '1 day'),
    ('{PRICE_OPENAI_RESP_ID}', '{VM_OPENAI_RESP_ID}', 'TOKEN', 1000000, 2000000, 100000, 1250000, 2000000, 0, now() - interval '1 day'),
    ('{PRICE_ANTHROPIC_ID}', '{VM_ANTHROPIC_ID}', 'TOKEN', 1000000, 2000000, 100000, 1250000, 2000000, 0, now() - interval '1 day'),
    ('{PRICE_GEMINI_ID}', '{VM_GEMINI_ID}', 'TOKEN', 1000000, 2000000, 100000, 1250000, 2000000, 0, now() - interval '1 day')
ON CONFLICT (id) DO NOTHING;

COMMIT;
"#
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encrypt_envelope_layout_matches_aes_gcm_cipher() {
        let key = base64::engine::general_purpose::STANDARD
            .decode("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
            .unwrap();
        assert_eq!(key.len(), 32);
        let plaintext = r#"{"apiKey":"sk-mock-openai"}"#;
        let aad = CH_OPENAI_ID;

        let hex_env = encrypt_envelope_hex(&key, plaintext, aad);
        let raw = hex::decode(&hex_env).unwrap();
        assert_eq!(raw[0], 0x01);
        let iv = &raw[1..13];
        let ct = &raw[13..];

        let cipher = Aes256Gcm::new_from_slice(&key).unwrap();
        let decrypted = cipher
            .decrypt(
                Nonce::from_slice(iv),
                Payload {
                    msg: ct,
                    aad: aad.as_bytes(),
                },
            )
            .unwrap();
        assert_eq!(std::str::from_utf8(&decrypted).unwrap(), plaintext);
    }

    #[test]
    fn hmac_sha256_produces_64_hex_chars() {
        let h = hmac_sha256_hex(
            "dev-only-hyperllm-key-pepper-v1-change-in-production",
            "sk-bench0010000000000000000000000000000000000000001",
        );
        assert_eq!(h.len(), 64);
    }
}

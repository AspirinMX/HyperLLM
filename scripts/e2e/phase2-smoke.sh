#!/usr/bin/env bash
# Phase 2 live-stack smoke test.
#
# Covers the commercial loop beyond Phase 1:
#   project budget -> topup -> voucher redemption -> pre-hold -> SSE + non-stream settlement
#   -> budget status -> usage report -> alert configuration -> reconciliation -> config CDC.
#
# Prerequisites:
#   docker compose -f infra/docker-compose.yaml --profile pipeline --profile tools up -d
#   console (:8081) with HYPERLLM_OTP_DEBUG_ECHO=true
#   admin   (:8082) with HYPERLLM_ADMIN_MFA_REQUIRED=false and bootstrap credentials
#   gateway (:8080)
#
# Overrides: ADMIN, CONSOLE, GATEWAY, WIREMOCK, CLICKHOUSE, ADMIN_LOGIN, ADMIN_PASSWORD,
#            TOPUP_MICROS, VOUCHER_MICROS, SKIP_USAGE=1.

set -eo pipefail

if [ -z "$ADMIN" ]; then ADMIN=http://localhost:8082; fi
if [ -z "$CONSOLE" ]; then CONSOLE=http://localhost:8081; fi
if [ -z "$GATEWAY" ]; then GATEWAY=http://localhost:8080; fi
if [ -z "$WIREMOCK" ]; then WIREMOCK=http://localhost:8099; fi
if [ -z "$CLICKHOUSE" ]; then CLICKHOUSE=http://localhost:8123; fi
if [ -z "$ADMIN_LOGIN" ]; then ADMIN_LOGIN=ops-root@hyperllm.dev; fi
if [ -z "$ADMIN_PASSWORD" ]; then ADMIN_PASSWORD=RootAdminPass123!; fi
if [ -z "$TOPUP_MICROS" ]; then TOPUP_MICROS=5000000; fi
if [ -z "$VOUCHER_MICROS" ]; then VOUCHER_MICROS=1000000; fi
if [ -z "$SKIP_USAGE" ]; then SKIP_USAGE=0; fi

STAMP=$(date +%s)
SMOKE_MODEL=smoke2-$STAMP
SSE_MODEL=smoke2sse-$STAMP

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

step() { printf '\n==> %s\n' "$1"; }
fail() {
  printf 'FAILED: %s\n' "$1" >&2
  if [ -f "$TMP/body" ]; then cat "$TMP/body" >&2; fi
  exit 1
}

flat() { printf '%s' "$1" | awk 'BEGIN{ORS=""}{print}'; }

field() { # json key -> first string value
  printf '%s' "$(flat "$1")" | sed -n 's/.*"'"$2"'":"\([^"]*\)".*/\1/p' | head -n 1
}

num() { # json key -> first numeric value
  printf '%s' "$(flat "$1")" | sed -n 's/.*"'"$2"'":\([-0-9]*\).*/\1/p' | head -n 1
}

first_code() {
  printf '%s' "$(flat "$1")" | sed -n 's/.*"codes":\["\([^"]*\)".*/\1/p' | head -n 1
}

object_with() {
  printf '%s' "$(flat "$1")" | awk -v RS='}' -v m="$2" 'index($0, m) { print; exit }'
}

http() { # method url token data
  if [ -n "$4" ]; then
    CODE=$(curl -sS -o "$TMP/body" -w '%{http_code}' -X "$1" -H 'Content-Type: application/json' -H "Authorization: Bearer $3" -d "$4" "$2")
  else
    CODE=$(curl -sS -o "$TMP/body" -w '%{http_code}' -X "$1" -H "Authorization: Bearer $3" "$2")
  fi
  BODY=$(cat "$TMP/body")
}

public() { # method url data
  if [ -n "$3" ]; then
    CODE=$(curl -sS -o "$TMP/body" -w '%{http_code}' -X "$1" -H 'Content-Type: application/json' -d "$3" "$2")
  else
    CODE=$(curl -sS -o "$TMP/body" -w '%{http_code}' -X "$1" "$2")
  fi
  BODY=$(cat "$TMP/body")
}

expect() {
  if [ "$CODE" != "$1" ]; then fail "$2 (HTTP $CODE, expected $1)"; fi
}

wait_for() {
  tries=0
  while [ $tries -lt 60 ]; do
    code=$(curl -sS -o "$TMP/body" -w '%{http_code}' "$1" || true)
    if [ "$code" = "200" ]; then return 0; fi
    tries=$((tries+1))
    sleep 1
  done
  fail "$2 is not reachable at $1"
}

step "Preflight"
wait_for "$ADMIN/actuator/health" admin
wait_for "$CONSOLE/actuator/health" console
wait_for "$GATEWAY/actuator/health" gateway
wait_for "$WIREMOCK/__admin/health" wiremock
printf 'admin=%s console=%s gateway=%s wiremock=%s clickhouse=%s\n' \
  "$ADMIN" "$CONSOLE" "$GATEWAY" "$WIREMOCK" "$CLICKHOUSE"

step "Admin login"
public POST "$ADMIN/api/v1/admin/auth/login" '{"login":"'$ADMIN_LOGIN'","password":"'$ADMIN_PASSWORD'"}'
expect 200 "admin login"
ADMIN_TOKEN=$(field "$BODY" accessToken)
if [ -z "$ADMIN_TOKEN" ]; then fail "admin login returned no accessToken (run with HYPERLLM_ADMIN_MFA_REQUIRED=false)"; fi

step "Resolve OPENAI provider"
http GET "$ADMIN/api/v1/admin/providers" "$ADMIN_TOKEN" ""
expect 200 "list providers"
PROVIDER_ID=$(field "$(object_with "$BODY" OPENAI)" id)
if [ -z "$PROVIDER_ID" ]; then fail "OPENAI provider template not found"; fi

step "Create a failing and a healthy channel (retry regression)"
http POST "$ADMIN/api/v1/admin/channels" "$ADMIN_TOKEN" '{"providerId":"'$PROVIDER_ID'","name":"smoke2-fail-'$STAMP'","baseUrlOverride":"'$WIREMOCK'/fail/v1","apiKey":"sk-mock-fail","defaultCostDiscountBps":10000}'
expect 201 "create failing channel"
FAIL_CHANNEL=$(field "$BODY" id)
http POST "$ADMIN/api/v1/admin/channels" "$ADMIN_TOKEN" '{"providerId":"'$PROVIDER_ID'","name":"smoke2-ok-'$STAMP'","baseUrlOverride":"'$WIREMOCK'/ok/v1","apiKey":"sk-mock-ok","defaultCostDiscountBps":10000}'
expect 201 "create healthy channel"
CHANNEL_ID=$(field "$BODY" id)

step "Create virtual model $SMOKE_MODEL"
http POST "$ADMIN/api/v1/admin/models" "$ADMIN_TOKEN" '{"modelCode":"'$SMOKE_MODEL'","displayName":"Phase 2 Smoke","capabilityType":"CHAT","nativeProtocol":"OPENAI","contextWindow":128000,"routingStrategy":"PRIORITY_WEIGHTED","stickyCacheEnabled":false,"stickyTtlSeconds":300,"visibility":"PUBLIC"}'
expect 201 "create model"
MODEL_ID=$(field "$BODY" id)

step "Bind both channels and publish pricing"
http PUT "$ADMIN/api/v1/admin/models/$MODEL_ID/bindings" "$ADMIN_TOKEN" '{"bindings":[{"channelId":"'$FAIL_CHANNEL'","upstreamModelName":"gpt-4o-fail","priorityTier":1,"weight":100},{"channelId":"'$CHANNEL_ID'","upstreamModelName":"gpt-4o-mock","priorityTier":2,"weight":100}]}'
expect 200 "replace bindings"
http POST "$ADMIN/api/v1/admin/models/$MODEL_ID/pricing-rules" "$ADMIN_TOKEN" '{"billingMode":"TOKEN","inputPriceMicros1m":2500000,"outputPriceMicros1m":10000000,"effectiveFrom":"2020-01-01T00:00:00Z"}'
expect 201 "publish pricing"

step "Create an SSE model and channel"
http POST "$ADMIN/api/v1/admin/channels" "$ADMIN_TOKEN" '{"providerId":"'$PROVIDER_ID'","name":"smoke2-sse-'$STAMP'","baseUrlOverride":"'$WIREMOCK'/sse/v1","apiKey":"sk-mock-sse","defaultCostDiscountBps":10000}'
expect 201 "create sse channel"
SSE_CHANNEL=$(field "$BODY" id)
http POST "$ADMIN/api/v1/admin/models" "$ADMIN_TOKEN" '{"modelCode":"'$SSE_MODEL'","displayName":"Phase 2 SSE","capabilityType":"CHAT","nativeProtocol":"OPENAI","contextWindow":128000,"routingStrategy":"PRIORITY_WEIGHTED","stickyCacheEnabled":false,"stickyTtlSeconds":300,"visibility":"PUBLIC"}'
expect 201 "create sse model"
SSE_MODEL_ID=$(field "$BODY" id)
http PUT "$ADMIN/api/v1/admin/models/$SSE_MODEL_ID/bindings" "$ADMIN_TOKEN" '{"bindings":[{"channelId":"'$SSE_CHANNEL'","upstreamModelName":"gpt-4o-sse","priorityTier":1,"weight":100}]}'
expect 200 "bind sse channel"
http POST "$ADMIN/api/v1/admin/models/$SSE_MODEL_ID/pricing-rules" "$ADMIN_TOKEN" '{"billingMode":"TOKEN","inputPriceMicros1m":2500000,"outputPriceMicros1m":10000000,"effectiveFrom":"2020-01-01T00:00:00Z"}'
expect 201 "publish sse pricing"

step "Register a developer"
EMAIL=smoke2-$STAMP@example.com
public POST "$CONSOLE/api/v1/auth/email-codes" '{"email":"'$EMAIL'","purpose":"REGISTER"}'
expect 200 "request email code"
OTP=$(field "$BODY" debugCode)
if [ -z "$OTP" ]; then fail "no debugCode (run console with HYPERLLM_OTP_DEBUG_ECHO=true)"; fi
public POST "$CONSOLE/api/v1/auth/register" '{"email":"'$EMAIL'","password":"SmokePass123!","displayName":"Smoke 2","emailCode":"'$OTP'"}'
expect 201 "register"
CONSOLE_TOKEN=$(field "$BODY" accessToken)
ORG_ID=$(field "$BODY" defaultOrgId)

step "Create a project with a monthly hard budget"
http POST "$CONSOLE/api/v1/orgs/$ORG_ID/projects" "$CONSOLE_TOKEN" '{"name":"phase2-'$STAMP'","environment":"PRODUCTION","monthlyHardCapMicros":100000000,"monthlySoftCapMicros":50000000,"rpmLimit":120}'
expect 201 "create project"
PROJECT_ID=$(field "$BODY" id)

step "Create an API key on the budgeted project"
http POST "$CONSOLE/api/v1/projects/$PROJECT_ID/api-keys" "$CONSOLE_TOKEN" '{"name":"phase2-key-'$STAMP'","rpmLimit":60,"tpmLimit":200000}'
expect 201 "create api key"
RAW_KEY=$(field "$BODY" rawKey)
KEY_ID=$(field "$BODY" id)

step "Top up the wallet (outbox, 202)"
http POST "$ADMIN/api/v1/admin/orgs/$ORG_ID/wallet/topup" "$ADMIN_TOKEN" '{"topupType":"CASH","amountMicros":'$TOPUP_MICROS',"idempotencyKey":"smoke2-topup-'$STAMP'","description":"phase2 smoke"}'
if [ "$CODE" != "200" ] && [ "$CODE" != "202" ]; then fail "topup (HTTP $CODE)"; fi
for i in $(seq 1 30); do
  http GET "$CONSOLE/api/v1/orgs/$ORG_ID/wallet" "$CONSOLE_TOKEN" ""
  CASH=$(num "$BODY" cashBalanceMicros)
  if [ -n "$CASH" ] && [ "$CASH" -ge "$TOPUP_MICROS" ]; then break; fi
  sleep 1
done
if [ -z "$CASH" ] || [ "$CASH" -lt "$TOPUP_MICROS" ]; then fail "topup did not reach the wallet"; fi

step "Issue and redeem a voucher"
http POST "$ADMIN/api/v1/admin/vouchers" "$ADMIN_TOKEN" '{"name":"smoke2-voucher-'$STAMP'","voucherType":"PROMO_CODE","amountMicros":'$VOUCHER_MICROS',"validDays":30,"maxRedemptionsPerOrg":1}'
expect 201 "create voucher"
VOUCHER_ID=$(field "$BODY" id)
http POST "$ADMIN/api/v1/admin/vouchers/$VOUCHER_ID/code-batches" "$ADMIN_TOKEN" '{"quantity":1}'
expect 201 "create code batch"
CODE=$(first_code "$BODY")
if [ -z "$CODE" ]; then fail "no plaintext code returned"; fi
http POST "$CONSOLE/api/v1/orgs/$ORG_ID/redemptions" "$CONSOLE_TOKEN" '{"code":"'$CODE'"}'
expect 202 "redeem code"
for i in $(seq 1 30); do
  http GET "$CONSOLE/api/v1/orgs/$ORG_ID/wallet" "$CONSOLE_TOKEN" ""
  BONUS=$(num "$BODY" bonusBalanceMicros)
  if [ -n "$BONUS" ] && [ "$BONUS" -ge "$VOUCHER_MICROS" ]; then break; fi
  sleep 1
done
if [ -z "$BONUS" ] || [ "$BONUS" -lt "$VOUCHER_MICROS" ]; then fail "voucher grant did not reach the wallet"; fi

step "Non-stream call through the failing channel (retry regression)"
http POST "$GATEWAY/v1/chat/completions" "$RAW_KEY" '{"model":"'$SMOKE_MODEL'","stream":false,"messages":[{"role":"user","content":"ping"}]}'
expect 200 "gateway call"
CONTENT=$(field "$BODY" content)
if [ -z "$CONTENT" ]; then fail "gateway response has no content"; fi

step "SSE call"
curl -sS -N -o "$TMP/sse" -w '%{http_code}' -X POST "$GATEWAY/v1/chat/completions" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $RAW_KEY" \
  -d '{"model":"'$SSE_MODEL'","stream":true,"messages":[{"role":"user","content":"stream"}]}' > "$TMP/sse_code"
if [ "$(cat "$TMP/sse_code")" != "200" ]; then fail "streaming call (HTTP $(cat "$TMP/sse_code"))"; fi
if ! grep -q '^data:' "$TMP/sse"; then fail "streaming response carried no SSE data frames"; fi

step "Wait for settlements and read the project budget"
tries=0
TOTAL=0
while [ $tries -lt 45 ]; do
  http GET "$CONSOLE/api/v1/orgs/$ORG_ID/wallet/ledger?page=1&pageSize=20" "$CONSOLE_TOKEN" ""
  expect 200 "read ledger"
  TOTAL=$(num "$BODY" total)
  if [ -n "$TOTAL" ] && [ "$TOTAL" -ge 4 ]; then break; fi
  tries=$((tries+1))
  sleep 1
done
if [ "$TOTAL" -lt 4 ]; then fail "settlements did not reach the ledger (rows=$TOTAL)"; fi
http GET "$CONSOLE/api/v1/projects/$PROJECT_ID/budget" "$CONSOLE_TOKEN" ""
expect 200 "read project budget"
BUDGET_USED=$(num "$BODY" usedMicros)
BUDGET_SOURCE=$(field "$BODY" source)
if [ -z "$BUDGET_USED" ] || [ "$BUDGET_USED" -le 0 ]; then fail "budget used is not positive"; fi

step "Configure a low-balance alert"
http POST "$CONSOLE/api/v1/orgs/$ORG_ID/notification-endpoints" "$CONSOLE_TOKEN" '{"channelType":"WEBHOOK","target":"https://example.com/hyperllm-alert"}'
expect 201 "create endpoint"
ENDPOINT_ID=$(field "$BODY" id)
http POST "$CONSOLE/api/v1/orgs/$ORG_ID/alert-rules" "$CONSOLE_TOKEN" '{"alertType":"LOW_BALANCE","thresholdMicros":1,"endpointIds":["'$ENDPOINT_ID'"],"cooldownMinutes":60}'
expect 201 "create alert rule"

step "Request report"
if [ "$SKIP_USAGE" = "1" ]; then
  printf 'skipped (SKIP_USAGE=1)\n'
else
  FROM=$(date -u -d '-2 hours' +%Y-%m-%dT%H:%M:%SZ)
  TO=$(date -u -d '+10 minutes' +%Y-%m-%dT%H:%M:%SZ)
  REQUESTS=0
  tries=0
  while [ $tries -lt 60 ]; do
    code=$(curl -sS -o "$TMP/body" -w '%{http_code}' -H "Authorization: Bearer $CONSOLE_TOKEN" \
      "$CONSOLE/api/v1/orgs/$ORG_ID/usage?from=$FROM&to=$TO&projectId=$PROJECT_ID&groupBy=MODEL" || true)
    if [ "$code" = "200" ]; then
      BODY=$(cat "$TMP/body")
      REQUESTS=$(num "$BODY" requests)
      if [ -n "$REQUESTS" ] && [ "$REQUESTS" -ge 1 ]; then break; fi
    fi
    tries=$((tries+1))
    sleep 2
  done
  if [ -z "$REQUESTS" ] || [ "$REQUESTS" -lt 1 ]; then fail "usage report did not show the calls"; fi
fi

step "Run reconciliation"
http POST "$ADMIN/api/v1/admin/reconciliation/run" "$ADMIN_TOKEN" '{}'
expect 200 "reconciliation run"
http GET "$ADMIN/api/v1/admin/reconciliation/findings?state=OPEN&limit=50" "$ADMIN_TOKEN" ""
expect 200 "list findings"

step "Configuration CDC eviction (revoked key returns 401)"
http DELETE "$CONSOLE/api/v1/api-keys/$KEY_ID?version=0" "$CONSOLE_TOKEN" ""
expect 204 "revoke api key"
REVOKED=0
for i in $(seq 1 30); do
  code=$(curl -sS -o "$TMP/body" -w '%{http_code}' -X POST "$GATEWAY/v1/chat/completions" \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $RAW_KEY" \
    -d '{"model":"'$SMOKE_MODEL'","messages":[{"role":"user","content":"ping"}]}' || true)
  if [ "$code" = "401" ]; then REVOKED=1; break; fi
  sleep 1
done
if [ "$REVOKED" != "1" ]; then fail "revoked key was still accepted after 30s"; fi

printf '\nPHASE 2 SMOKE PASSED\n'
printf '  model        : %s\n' "$SMOKE_MODEL"
printf '  org          : %s\n' "$ORG_ID"
printf '  project      : %s\n' "$PROJECT_ID"
printf '  ledger rows  : %s\n' "$TOTAL"
printf '  cash         : %s micro-USD\n' "$CASH"
printf '  bonus        : %s micro-USD\n' "$BONUS"
printf '  budget used  : %s micro-USD (%s)\n' "$BUDGET_USED" "$BUDGET_SOURCE"
printf '  usage calls  : %s\n' "$REQUESTS"

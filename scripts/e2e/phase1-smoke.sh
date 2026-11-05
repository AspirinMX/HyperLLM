#!/usr/bin/env bash
# Phase 1 live-stack smoke test.
#
# Covers the whole control/data plane loop against real infrastructure:
#   admin login -> channel/model/binding/pricing -> console signup -> admin topup
#   -> API key -> gateway call -> asynchronous Kafka settlement -> wallet ledger row.
#
# Prerequisites:
#   docker compose -f infra/docker-compose.yaml --profile tools up -d
#   console (:8081) with HYPERLLM_OTP_DEBUG_ECHO=true
#   admin   (:8082) with HYPERLLM_ADMIN_MFA_REQUIRED=false and bootstrap credentials
#   gateway (:8080)
#
# Overrides: ADMIN, CONSOLE, GATEWAY, WIREMOCK, ADMIN_LOGIN, ADMIN_PASSWORD, TOPUP_MICROS.

set -eo pipefail

if [ -z "$ADMIN" ]; then ADMIN=http://localhost:8082; fi
if [ -z "$CONSOLE" ]; then CONSOLE=http://localhost:8081; fi
if [ -z "$GATEWAY" ]; then GATEWAY=http://localhost:8080; fi
if [ -z "$WIREMOCK" ]; then WIREMOCK=http://localhost:8099; fi
if [ -z "$ADMIN_LOGIN" ]; then ADMIN_LOGIN=ops-root@hyperllm.dev; fi
if [ -z "$ADMIN_PASSWORD" ]; then ADMIN_PASSWORD=RootAdminPass123!; fi
if [ -z "$TOPUP_MICROS" ]; then TOPUP_MICROS=1000000; fi

STAMP=$(date +%s)
SMOKE_MODEL=smoke-$STAMP

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

object_with() { # json marker -> the object fragment containing the marker
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

expect() { # expected-code label
  if [ "$CODE" != "$1" ]; then fail "$2 (HTTP $CODE, expected $1)"; fi
}

wait_for() { # url label
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
printf 'admin=%s console=%s gateway=%s wiremock=%s\n' "$ADMIN" "$CONSOLE" "$GATEWAY" "$WIREMOCK"

step "Admin login"
public POST "$ADMIN/api/v1/admin/auth/login" '{"login":"'$ADMIN_LOGIN'","password":"'$ADMIN_PASSWORD'"}'
expect 200 "admin login"
ADMIN_TOKEN=$(field "$BODY" accessToken)
if [ -z "$ADMIN_TOKEN" ]; then
  MFA_ACTION=$(field "$BODY" mfaAction)
  fail "admin login returned no accessToken (mfaAction=$MFA_ACTION); start admin with HYPERLLM_ADMIN_MFA_REQUIRED=false"
fi

step "Resolve OPENAI provider"
http GET "$ADMIN/api/v1/admin/providers" "$ADMIN_TOKEN" ""
expect 200 "list providers"
PROVIDER_ID=$(field "$(object_with "$BODY" OPENAI)" id)
if [ -z "$PROVIDER_ID" ]; then fail "OPENAI provider template not found"; fi

step "Create upstream channel pointing at WireMock"
http POST "$ADMIN/api/v1/admin/channels" "$ADMIN_TOKEN" '{"providerId":"'$PROVIDER_ID'","name":"smoke-channel-'$STAMP'","baseUrlOverride":"'$WIREMOCK'/v1","apiKey":"sk-mock-upstream","defaultCostDiscountBps":10000}'
expect 201 "create channel"
CHANNEL_ID=$(field "$BODY" id)

step "Create virtual model $SMOKE_MODEL"
http POST "$ADMIN/api/v1/admin/models" "$ADMIN_TOKEN" '{"modelCode":"'$SMOKE_MODEL'","displayName":"Smoke Model","capabilityType":"CHAT","nativeProtocol":"OPENAI","contextWindow":128000,"routingStrategy":"PRIORITY_WEIGHTED","stickyCacheEnabled":false,"stickyTtlSeconds":300,"visibility":"PUBLIC"}'
expect 201 "create model"
MODEL_ID=$(field "$BODY" id)

step "Bind channel and publish pricing"
http PUT "$ADMIN/api/v1/admin/models/$MODEL_ID/bindings" "$ADMIN_TOKEN" '{"bindings":[{"channelId":"'$CHANNEL_ID'","upstreamModelName":"gpt-4o-mock","priorityTier":1,"weight":100}]}'
expect 200 "replace bindings"
http POST "$ADMIN/api/v1/admin/models/$MODEL_ID/pricing-rules" "$ADMIN_TOKEN" '{"billingMode":"TOKEN","inputPriceMicros1m":2500000,"outputPriceMicros1m":10000000,"effectiveFrom":"2020-01-01T00:00:00Z"}'
expect 201 "publish pricing"

step "Register a developer through the console"
EMAIL=smoke-$STAMP@example.com
public POST "$CONSOLE/api/v1/auth/email-codes" '{"email":"'$EMAIL'","purpose":"REGISTER"}'
expect 200 "request email code"
OTP=$(field "$BODY" debugCode)
if [ -z "$OTP" ]; then fail "no debugCode returned; start console with HYPERLLM_OTP_DEBUG_ECHO=true"; fi
public POST "$CONSOLE/api/v1/auth/register" '{"email":"'$EMAIL'","password":"SmokePass123!","displayName":"Smoke","emailCode":"'$OTP'"}'
expect 201 "register"
CONSOLE_TOKEN=$(field "$BODY" accessToken)
ORG_ID=$(field "$BODY" defaultOrgId)
if [ -z "$ORG_ID" ]; then fail "register response has no defaultOrgId"; fi

step "Top up the organization wallet"
http POST "$ADMIN/api/v1/admin/orgs/$ORG_ID/wallet/topup" "$ADMIN_TOKEN" '{"topupType":"CASH","amountMicros":'$TOPUP_MICROS',"idempotencyKey":"smoke-topup-'$STAMP'","description":"phase1 smoke"}'
if [ "$CODE" != "200" ] && [ "$CODE" != "202" ]; then fail "topup (HTTP $CODE)"; fi

step "Create an API key on the default project"
http GET "$CONSOLE/api/v1/orgs/$ORG_ID/projects?page=1&pageSize=50" "$CONSOLE_TOKEN" ""
expect 200 "list projects"
PROJECT_ID=$(field "$(object_with "$BODY" default)" id)
if [ -z "$PROJECT_ID" ]; then fail "default project not found"; fi
http POST "$CONSOLE/api/v1/projects/$PROJECT_ID/api-keys" "$CONSOLE_TOKEN" '{"name":"smoke-key-'$STAMP'","rpmLimit":60}'
expect 201 "create api key"
RAW_KEY=$(field "$BODY" rawKey)

step "Call the gateway"
http POST "$GATEWAY/v1/chat/completions" "$RAW_KEY" '{"model":"'$SMOKE_MODEL'","stream":false,"messages":[{"role":"user","content":"ping"}]}'
expect 200 "gateway call"
CONTENT=$(field "$BODY" content)
if [ -z "$CONTENT" ]; then fail "gateway response has no assistant content"; fi
printf 'assistant=%s\n' "$CONTENT"

step "Wait for asynchronous settlement to reach wallet_ledger"
TOTAL=0
tries=0
while [ $tries -lt 30 ]; do
  http GET "$CONSOLE/api/v1/orgs/$ORG_ID/wallet/ledger?page=1&pageSize=10" "$CONSOLE_TOKEN" ""
  expect 200 "read wallet ledger"
  TOTAL=$(num "$BODY" total)
  if [ -n "$TOTAL" ] && [ "$TOTAL" -ge 1 ]; then break; fi
  TOTAL=0
  tries=$((tries+1))
  sleep 1
done
if [ "$TOTAL" -lt 1 ]; then fail "no ledger row within 30s; is admin consuming llm.billing.settlement?"; fi

http GET "$CONSOLE/api/v1/orgs/$ORG_ID/wallet" "$CONSOLE_TOKEN" ""
expect 200 "read wallet"
CASH_BALANCE=$(num "$BODY" cashBalanceMicros)
CONSUMED=$(num "$BODY" totalConsumedCashMicros)

printf '\nSMOKE PASSED\n'
printf '  model       : %s\n' "$SMOKE_MODEL"
printf '  org         : %s\n' "$ORG_ID"
printf '  project     : %s\n' "$PROJECT_ID"
printf '  channel     : %s\n' "$CHANNEL_ID"
printf '  ledger rows : %s\n' "$TOTAL"
printf '  cash balance: %s micro-USD\n' "$CASH_BALANCE"
printf '  consumed    : %s micro-USD\n' "$CONSUMED"

-- Freezes one request's share of every batch it is about to consume.
--
-- Freezing per batch is what makes the reservation exact: a batch can never be handed to two
-- requests, so settling the batches later cannot overdraw them. Every leg is checked against
-- "remaining minus held" inside the same script, and the hold records the legs verbatim so the
-- settlement later charges the batches that were actually frozen.
--
-- Rate limits are rolling windows of one-second buckets: the zset orders the buckets, and the two
-- hashes hold the admitted requests and the reserved tokens of each bucket. A request that is
-- rejected leaves both hashes untouched.
--
-- KEYS[1] wallet, [2] done marker, [3] hold hash, [4] holds expiry zset,
-- [5..7] key window zset / request hash / token hash,
-- [8..10] project window zset / request hash / token hash,
-- [11..13] key, project, organisation concurrency counters
-- ARGV[1] holdId, [2] nowMs, [3] ttlMs, [4] legs, [5] renew flag, [6] orgId, [7] projectId,
--       [8] apiKeyId, [9] modelCode, [10] createdAtIso, [11] pack prefix, [12] bonus prefix,
--       [13] key rpm limit, [14] project rpm limit, [15] key concurrency, [16] project concurrency,
--       [17] org concurrency, [18] request id, [19] floor legs, [20] floor detail prefix,
--       [21] key tpm limit, [22] project tpm limit, [23] estimated tokens, [24] window seconds
--
-- Legs are encoded as "TYPE|id|units|micros" joined by ";". CASH legs carry no id and no units.
local function splitLegs(raw)
    local legs = {}
    for segment in string.gmatch(raw, '([^;]+)') do
        local kind, id, units, micros = string.match(segment, '([^|]*)|([^|]*)|([^|]*)|([^|]*)')
        if kind then
            table.insert(legs, {
                kind = kind,
                id = id,
                units = tonumber(units) or 0,
                micros = tonumber(micros) or 0
            })
        end
    end
    return legs
end

if redis.call('EXISTS', KEYS[3]) == 1 then
    return 'OK'
end
if redis.call('EXISTS', KEYS[2]) == 1 then
    return 'REJECTED'
end
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 'MISS_WALLET'
end
if redis.call('HGET', KEYS[1], 'status') ~= 'ACTIVE' then
    return 'ORG_SUSPENDED'
end

local legs = splitLegs(ARGV[4])
if #legs == 0 then
    return 'EMPTY_ALLOCATION'
end

local packPrefix = ARGV[11]
local bonusPrefix = ARGV[12]
local cash = tonumber(redis.call('HGET', KEYS[1], 'cash_micros') or '0') or 0
local heldCash = tonumber(redis.call('HGET', KEYS[1], 'held_cash_micros') or '0') or 0
local cashNeeded = 0
local frozen = {}

for _, leg in ipairs(legs) do
    if leg.kind == 'CASH' then
        cashNeeded = cashNeeded + leg.micros
    else
        local itemKey
        if leg.kind == 'PACK' then
            itemKey = packPrefix .. leg.id
        else
            itemKey = bonusPrefix .. leg.id
        end
        if redis.call('EXISTS', itemKey) == 0 then
            return 'MISS_ENTITLEMENTS'
        end
        if leg.kind == 'PACK' then
            local remaining = tonumber(redis.call('HGET', itemKey, 'remaining_units') or '0') or 0
            local held = tonumber(redis.call('HGET', itemKey, 'held_units') or '0') or 0
            if remaining - held < leg.units then
                return 'INSUFFICIENT_ENTITLEMENT'
            end
            table.insert(frozen, {key = itemKey, field = 'held_units', amount = leg.units})
        else
            local remaining = tonumber(redis.call('HGET', itemKey, 'remaining_micros') or '0') or 0
            local held = tonumber(redis.call('HGET', itemKey, 'held_micros') or '0') or 0
            if remaining - held < leg.micros then
                return 'INSUFFICIENT_ENTITLEMENT'
            end
            table.insert(frozen, {key = itemKey, field = 'held_micros', amount = leg.micros})
        end
    end
end

if cash - heldCash < cashNeeded then
    return 'INSUFFICIENT_BALANCE'
end

local nowMs = tonumber(ARGV[2]) or 0
local nowSec = math.floor(nowMs / 1000)
local window = math.max(1, math.min(300, tonumber(ARGV[24]) or 60))
local from = nowSec - window + 1

-- Evicts buckets that left the window, then sums what remains. The eviction happens before the
-- limit check and only removes data that can no longer contribute, so a rejection changes no usage.
local function windowState(zset, counts, tokens)
    local expired = redis.call('ZRANGEBYSCORE', zset, '-inf', from - 1)
    if #expired > 0 then
        redis.call('ZREM', zset, unpack(expired))
        for _, member in ipairs(expired) do
            redis.call('HDEL', counts, member)
            redis.call('HDEL', tokens, member)
        end
    end
    local fields = {}
    for second = from, nowSec do
        fields[#fields + 1] = tostring(second)
    end
    local requests, used = 0, 0
    if #fields > 0 then
        local countValues = redis.call('HMGET', counts, unpack(fields))
        local tokenValues = redis.call('HMGET', tokens, unpack(fields))
        for i = 1, #fields do
            requests = requests + (tonumber(countValues[i]) or 0)
            used = used + (tonumber(tokenValues[i]) or 0)
        end
    end
    return requests, used
end

local estimated = math.max(0, tonumber(ARGV[23]) or 0)
local keyRequests, keyTokens = windowState(KEYS[5], KEYS[6], KEYS[7])
local projectRequests, projectTokens = windowState(KEYS[8], KEYS[9], KEYS[10])
local keyRpmLimit = tonumber(ARGV[13]) or 0
local projectRpmLimit = tonumber(ARGV[14]) or 0
local keyTpmLimit = tonumber(ARGV[21]) or 0
local projectTpmLimit = tonumber(ARGV[22]) or 0
if keyRpmLimit > 0 and keyRequests + 1 > keyRpmLimit then
    return 'RATE_LIMIT'
end
if projectRpmLimit > 0 and projectRequests + 1 > projectRpmLimit then
    return 'RATE_LIMIT'
end
if keyTpmLimit > 0 and keyTokens + estimated > keyTpmLimit then
    return 'RATE_LIMIT'
end
if projectTpmLimit > 0 and projectTokens + estimated > projectTpmLimit then
    return 'RATE_LIMIT'
end

local limits = {tonumber(ARGV[15]) or 0, tonumber(ARGV[16]) or 0, tonumber(ARGV[17]) or 0}
local acquired = {}
for i = 1, 3 do
    if limits[i] > 0 then
        local concKey = KEYS[10 + i]
        local current = redis.call('INCR', concKey)
        redis.call('PEXPIRE', concKey, tonumber(ARGV[3]) * 2)
        table.insert(acquired, concKey)
        if current > limits[i] then
            for _, key in ipairs(acquired) do
                redis.call('DECR', key)
            end
            return 'RATE_LIMIT'
        end
    end
end

local windowTtl = (window + 60) * 1000
local function admitWindow(zset, counts, tokens)
    redis.call('ZADD', zset, nowSec, nowSec)
    redis.call('HINCRBY', counts, nowSec, 1)
    if estimated > 0 then
        redis.call('HINCRBY', tokens, nowSec, estimated)
    end
    redis.call('PEXPIRE', zset, windowTtl)
    redis.call('PEXPIRE', counts, windowTtl)
    redis.call('PEXPIRE', tokens, windowTtl)
end
admitWindow(KEYS[5], KEYS[6], KEYS[7])
admitWindow(KEYS[8], KEYS[9], KEYS[10])

local holdId = ARGV[1]
local ttlMs = tonumber(ARGV[3])
redis.call('HSET', KEYS[3],
        'org_id', ARGV[6],
        'project_id', ARGV[7],
        'api_key_id', ARGV[8],
        'model_code', ARGV[9],
        'created_at', ARGV[10],
        'legs', ARGV[4],
        'request_id', ARGV[18],
        'fallback_legs', ARGV[19],
        'fallback_detail', ARGV[20],
        'concurrency_keys', table.concat(acquired, '|'),
        'rate_tokens', KEYS[7] .. '|' .. KEYS[10],
        'rate_second', tostring(nowSec),
        'rate_estimate', tostring(estimated),
        'served', '0',
        'cash_micros', tostring(cashNeeded))
redis.call('ZADD', KEYS[4], nowMs + ttlMs, holdId)

for _, item in ipairs(frozen) do
    redis.call('HINCRBY', item.key, item.field, item.amount)
end
if cashNeeded > 0 then
    redis.call('HINCRBY', KEYS[1], 'held_cash_micros', cashNeeded)
end

-- A reservation keeps its authority alive through expiry and one sweep window.
local function protect(key)
    local remaining = redis.call('PTTL', key)
    if remaining >= 0 and remaining < ttlMs * 2 then
        redis.call('PEXPIRE', key, ttlMs * 2)
    end
end
protect(KEYS[1])
for _, reservation in ipairs(frozen) do
    protect(reservation.key)
end

return 'OK'

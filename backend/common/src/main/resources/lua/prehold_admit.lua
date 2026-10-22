-- Freezes one request's share of every batch it is about to consume.
--
-- Freezing per batch is what makes the reservation exact: a batch can never be handed to two
-- requests, so settling the batches later cannot overdraw them. Every leg is checked against
-- "remaining minus held" inside the same script, and the hold records the legs verbatim so the
-- settlement later charges the batches that were actually frozen.
--
-- A missing batch key aborts without writing anything: the mirror is loaded by a separate script,
-- and writing here would resurrect a half-built tenant.
--
-- KEYS[1] wallet hash
-- KEYS[2] fold position of the organisation
-- KEYS[3] hold hash
-- KEYS[4] holds expiry zset
-- KEYS[5] key rpm counter, [6] project rpm counter, [7..9] concurrency counters
-- ARGV[1] holdId, [2] nowMs, [3] ttlMs, [4] legs, [5] renew flag, [6] orgId, [7] projectId,
--       [8] apiKeyId, [9] modelCode, [10] createdAtIso, [11] pack prefix, [12] bonus prefix,
--       [13] key rpm limit, [14] project rpm limit, [15] key concurrency, [16] project concurrency,
--       [17] org concurrency
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

local keyRpmLimit = tonumber(ARGV[13]) or 0
local projectRpmLimit = tonumber(ARGV[14]) or 0
if keyRpmLimit > 0 and (tonumber(redis.call('GET', KEYS[5]) or '0') or 0) >= keyRpmLimit then
    return 'RATE_LIMIT'
end
if projectRpmLimit > 0 and (tonumber(redis.call('GET', KEYS[6]) or '0') or 0) >= projectRpmLimit then
    return 'RATE_LIMIT'
end

local limits = {tonumber(ARGV[15]) or 0, tonumber(ARGV[16]) or 0, tonumber(ARGV[17]) or 0}
local acquired = {}
for i = 1, 3 do
    if limits[i] > 0 then
        local concKey = KEYS[6 + i]
        local current = redis.call('INCR', concKey)
        redis.call('PEXPIRE', concKey, tonumber(ARGV[3]))
        table.insert(acquired, concKey)
        if current > limits[i] then
            for _, key in ipairs(acquired) do
                redis.call('DECR', key)
            end
            return 'RATE_LIMIT'
        end
    end
end

if keyRpmLimit > 0 then
    if redis.call('INCR', KEYS[5]) == 1 then
        redis.call('PEXPIRE', KEYS[5], 120000)
    end
end
if projectRpmLimit > 0 then
    if redis.call('INCR', KEYS[6]) == 1 then
        redis.call('PEXPIRE', KEYS[6], 120000)
    end
end

local holdId = ARGV[1]
local ttlMs = tonumber(ARGV[3])
redis.call('HSET', KEYS[3],
        'org_id', ARGV[6],
        'project_id', ARGV[7],
        'api_key_id', ARGV[8],
        'model_code', ARGV[9],
        'created_at', ARGV[10],
        'legs', ARGV[4],
        'served', '0',
        'cash_micros', tostring(cashNeeded))
redis.call('PEXPIRE', KEYS[3], ttlMs * 2)
redis.call('ZADD', KEYS[4], tonumber(ARGV[2]) + ttlMs, holdId)

for _, item in ipairs(frozen) do
    redis.call('HINCRBY', item.key, item.field, item.amount)
end
if cashNeeded > 0 then
    redis.call('HINCRBY', KEYS[1], 'held_cash_micros', cashNeeded)
end

-- The fold position only moves forward, so a late admission can never rewind it.
local produced = redis.call('HGET', KEYS[2], 'produced')
if (not produced) or produced < holdId then
    if not produced then
        redis.call('HSET', KEYS[2], 'since', ARGV[10])
    end
    redis.call('HSET', KEYS[2], 'produced', holdId)
end

if ARGV[5] == '1' then
    redis.call('PEXPIRE', KEYS[1], ttlMs)
    local idx = 5
    for _, leg in ipairs(legs) do
        if leg.kind == 'PACK' then
            redis.call('PEXPIRE', packPrefix .. leg.id, ttlMs)
        elseif leg.kind == 'BONUS' then
            redis.call('PEXPIRE', bonusPrefix .. leg.id, ttlMs)
        end
    end
end

return 'OK'

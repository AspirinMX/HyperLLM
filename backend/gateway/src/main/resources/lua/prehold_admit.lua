-- Pre-hold admission: limits, tiered reservation of pack units / bonus / cash.
-- KEYS[1] wallet, [2] pack availability, [3] pack held, [4] hold hash, [5] holds expiry zset,
--       [6] org holds set, [7] key rpm, [8] project rpm, [9] key conc, [10] project conc, [11] org conc
-- ARGV[1] holdId, [2] nowMs, [3] ttlMs, [4] cashLeg, [5] bonusLeg, [6] packUnits,
--       [7] keyRpmLimit, [8] projectRpmLimit, [9] keyConcLimit, [10] projectConcLimit, [11] orgConcLimit,
--       [12] orgId, [13] projectId, [14] apiKeyId, [15] modelCode, [16] createdAtIso
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 'MISS_WALLET'
end

local status = redis.call('HGET', KEYS[1], 'status')
if status ~= 'ACTIVE' then
    return 'ORG_SUSPENDED'
end

local cashLeg = tonumber(ARGV[4]) or 0
local bonusLeg = tonumber(ARGV[5]) or 0
local packUnits = tonumber(ARGV[6]) or 0

local cash = tonumber(redis.call('HGET', KEYS[1], 'cash_micros') or '0') or 0
local heldCash = tonumber(redis.call('HGET', KEYS[1], 'held_cash_micros') or '0') or 0
if cash - heldCash < cashLeg then
    return 'INSUFFICIENT_BALANCE'
end

local bonus = tonumber(redis.call('HGET', KEYS[1], 'general_bonus_micros') or '0') or 0
local heldBonus = tonumber(redis.call('HGET', KEYS[1], 'held_bonus_micros') or '0') or 0
if bonus - heldBonus < bonusLeg then
    return 'INSUFFICIENT_BALANCE'
end

if packUnits > 0 then
    local available = tonumber(redis.call('GET', KEYS[2]) or '0') or 0
    local packHeld = tonumber(redis.call('GET', KEYS[3]) or '0') or 0
    if available - packHeld < packUnits then
        return 'INSUFFICIENT_ENTITLEMENT'
    end
end

local keyRpmLimit = tonumber(ARGV[7]) or 0
local projectRpmLimit = tonumber(ARGV[8]) or 0
if keyRpmLimit > 0 and (tonumber(redis.call('GET', KEYS[7]) or '0') or 0) >= keyRpmLimit then
    return 'RATE_LIMIT'
end
if projectRpmLimit > 0 and (tonumber(redis.call('GET', KEYS[8]) or '0') or 0) >= projectRpmLimit then
    return 'RATE_LIMIT'
end

local concLimits = { tonumber(ARGV[9]) or 0, tonumber(ARGV[10]) or 0, tonumber(ARGV[11]) or 0 }
local acquired = {}
for i = 1, 3 do
    local limit = concLimits[i]
    if limit > 0 then
        local concKey = KEYS[8 + i]
        local current = redis.call('INCR', concKey)
        redis.call('EXPIRE', concKey, math.ceil(tonumber(ARGV[3]) / 1000))
        table.insert(acquired, concKey)
        if current > limit then
            for _, key in ipairs(acquired) do
                redis.call('DECR', key)
            end
            return 'RATE_LIMIT'
        end
    end
end

if keyRpmLimit > 0 then
    local value = redis.call('INCR', KEYS[7])
    if value == 1 then
        redis.call('EXPIRE', KEYS[7], 120)
    end
end
if projectRpmLimit > 0 then
    local value = redis.call('INCR', KEYS[8])
    if value == 1 then
        redis.call('EXPIRE', KEYS[8], 120)
    end
end

local holdId = ARGV[1]
local expiresAt = tonumber(ARGV[2]) + tonumber(ARGV[3])
redis.call('HSET', KEYS[4],
        'org_id', ARGV[12],
        'project_id', ARGV[13],
        'api_key_id', ARGV[14],
        'model_code', ARGV[15],
        'cash_micros', cashLeg,
        'bonus_micros', bonusLeg,
        'pack_units', packUnits,
        'created_at', ARGV[16])
redis.call('PEXPIRE', KEYS[4], tonumber(ARGV[3]) * 2)
redis.call('ZADD', KEYS[5], expiresAt, holdId)
redis.call('SADD', KEYS[6], holdId)
if cashLeg > 0 then
    redis.call('HINCRBY', KEYS[1], 'held_cash_micros', cashLeg)
end
if bonusLeg > 0 then
    redis.call('HINCRBY', KEYS[1], 'held_bonus_micros', bonusLeg)
end
if packUnits > 0 then
    redis.call('INCRBY', KEYS[3], packUnits)
end

return 'OK'
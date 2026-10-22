-- Converts a pre-hold into a charge: releases what was frozen, then debits what was consumed.
--
-- The hold records the batches that were frozen, so the settlement charges exactly those and never
-- reaches for a batch some other request reserved. Charges are re-derived from the held legs by the
-- caller; a charge that names a batch the hold does not cover is refused rather than invented.
--
-- Deliberately still charges when the hold was already reclaimed: a crashed request must not become
-- a way to be served for free. The verdict reports that case so the caller can flag it.
--
-- KEYS[1] wallet hash, [2] hold, [3] done marker, [4] holds expiry zset,
--      [5] settlement payload copy, [6] settlements awaiting confirmation
-- ARGV[1] holdId, [2] nowMs, [3] cash to charge, [4] bonus to charge, [5] units to charge,
--       [6] charges, [7] event payload, [8] done TTL seconds, [9] payload TTL millis,
--       [10] pack prefix, [11] bonus prefix, [12] renew flag, [13] ttl millis
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
    return {'ALREADY_SETTLED', '0', '0', '0'}
end

local packPrefix = ARGV[10]
local bonusPrefix = ARGV[11]
local holdExists = redis.call('EXISTS', KEYS[2]) == 1
local keyById = {}

if holdExists then
    local held = splitLegs(redis.call('HGET', KEYS[2], 'legs') or '')
    for _, leg in ipairs(held) do
        local itemKey
        if leg.kind == 'PACK' then
            itemKey = packPrefix .. leg.id
            keyById[leg.id] = itemKey
            redis.call('HINCRBY', itemKey, 'held_units', -leg.units)
        elseif leg.kind == 'BONUS' then
            itemKey = bonusPrefix .. leg.id
            keyById[leg.id] = itemKey
            redis.call('HINCRBY', itemKey, 'held_micros', -leg.micros)
        end
    end
    local heldCash = tonumber(redis.call('HGET', KEYS[2], 'cash_micros') or '0') or 0
    if heldCash > 0 then
        redis.call('HINCRBY', KEYS[1], 'held_cash_micros', -heldCash)
    end
    redis.call('DEL', KEYS[2])
    redis.call('ZREM', KEYS[4], ARGV[1])
end

for _, leg in ipairs(splitLegs(ARGV[6])) do
    if leg.kind == 'PACK' then
        local itemKey = keyById[leg.id]
        if not itemKey then
            return {'MISS_ENTITLEMENTS', '0', '0', '0'}
        end
        redis.call('HINCRBY', itemKey, 'remaining_units', -leg.units)
    elseif leg.kind == 'BONUS' then
        local itemKey = keyById[leg.id]
        if not itemKey then
            return {'MISS_ENTITLEMENTS', '0', '0', '0'}
        end
        redis.call('HINCRBY', itemKey, 'remaining_micros', -leg.micros)
    end
end

-- The charge can exceed what the hold froze when the request cost more than estimated. Cash is
-- capped at what is actually available, and the caller learns the applied amount from the verdict
-- rather than being told a number Redis did not honour.
local requestedCash = tonumber(ARGV[3]) or 0
local cashBalance = tonumber(redis.call('HGET', KEYS[1], 'cash_micros') or '0') or 0
local cashCharge = math.min(requestedCash, math.max(cashBalance, 0))
if cashCharge > 0 then
    redis.call('HINCRBY', KEYS[1], 'cash_micros', -cashCharge)
end

redis.call('SET', KEYS[3], ARGV[7], 'PX', tonumber(ARGV[8]) * 1000)
redis.call('SET', KEYS[5], ARGV[7], 'PX', tonumber(ARGV[9]))
redis.call('ZADD', KEYS[6], tonumber(ARGV[2]), ARGV[1])

if ARGV[12] == '1' then
    redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[13]))
    for _, itemKey in pairs(keyById) do
        redis.call('PEXPIRE', itemKey, tonumber(ARGV[13]))
    end
end

return {'OK', tostring(cashCharge), tostring(tonumber(ARGV[4]) or 0), holdExists and '0' or '1',
        tostring(math.max(requestedCash - cashCharge, 0))}

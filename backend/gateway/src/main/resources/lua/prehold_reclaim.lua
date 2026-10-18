-- Reclaims a pre-hold whose lease expired.
--
-- The score comparison is the compare-and-set that keeps reclaim from racing a heartbeat: only a
-- hold whose expiry has not been renewed can be released.
--
-- KEYS[1] hold, [2] holds expiry zset, [3] wallet
-- ARGV[1] holdId, [2] nowMs, [3] pack prefix, [4] bonus prefix
local function splitLegs(raw)
    local legs = {}
    for segment in string.gmatch(raw, '([^;]+)') do
        local kind, id, units, micros = string.match(segment, '([^|]*)|([^|]*)|([^|]*)|([^|]*)')
        if kind then
            table.insert(legs, {kind = kind, id = id, units = tonumber(units) or 0, micros = tonumber(micros) or 0})
        end
    end
    return legs
end

local score = redis.call('ZSCORE', KEYS[2], ARGV[1])
if (not score) or tonumber(score) > tonumber(ARGV[2]) then
    return 'STALE'
end
if redis.call('EXISTS', KEYS[1]) == 0 then
    redis.call('ZREM', KEYS[2], ARGV[1])
    return 'STALE'
end

for _, leg in ipairs(splitLegs(redis.call('HGET', KEYS[1], 'legs') or '')) do
    if leg.kind == 'PACK' then
        redis.call('HINCRBY', ARGV[3] .. leg.id, 'held_units', -leg.units)
    elseif leg.kind == 'BONUS' then
        redis.call('HINCRBY', ARGV[4] .. leg.id, 'held_micros', -leg.micros)
    end
end

local heldCash = tonumber(redis.call('HGET', KEYS[1], 'cash_micros') or '0') or 0
if heldCash > 0 then
    redis.call('HINCRBY', KEYS[3], 'held_cash_micros', -heldCash)
end

redis.call('DEL', KEYS[1])
redis.call('ZREM', KEYS[2], ARGV[1])
return 'RELEASED'

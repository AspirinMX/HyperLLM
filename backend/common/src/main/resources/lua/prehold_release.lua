-- Hands a pre-hold's reservation back without charging anything.
--
-- KEYS[1] wallet, [2] hold, [3] done marker, [4] holds expiry zset
-- ARGV[1] holdId, [2] pack prefix, [3] bonus prefix, [4] done TTL seconds
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

if redis.call('EXISTS', KEYS[2]) == 0 then
    return 'ALREADY_RELEASED'
end

for _, leg in ipairs(splitLegs(redis.call('HGET', KEYS[2], 'legs') or '')) do
    if leg.kind == 'PACK' then
        redis.call('HINCRBY', ARGV[2] .. leg.id, 'held_units', -leg.units)
    elseif leg.kind == 'BONUS' then
        redis.call('HINCRBY', ARGV[3] .. leg.id, 'held_micros', -leg.micros)
    end
end

local heldCash = tonumber(redis.call('HGET', KEYS[2], 'cash_micros') or '0') or 0
if heldCash > 0 then
    redis.call('HINCRBY', KEYS[1], 'held_cash_micros', -heldCash)
end

redis.call('DEL', KEYS[2])
redis.call('ZREM', KEYS[4], ARGV[1])
redis.call('SET', KEYS[3], '{"released":true}', 'PX', tonumber(ARGV[4]) * 1000)
return 'RELEASED'

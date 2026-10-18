-- Extends a live hold and the buffers it depends on.
--
-- A long stream renews here and nowhere else, so this script must touch the wallet and every batch
-- the hold froze: if it did not, a stream longer than the TTL would find its own reservation gone
-- and the tenant would look suspended.
--
-- KEYS[1] hold, [2] holds expiry zset, [3] wallet, [4..6] concurrency counters
-- ARGV[1] holdId, [2] new expiry epoch ms, [3] ttl millis, [4] renew flag,
--       [5] pack prefix, [6] bonus prefix
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

if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end

local ttlMs = tonumber(ARGV[3])
redis.call('ZADD', KEYS[2], tonumber(ARGV[2]), ARGV[1])
redis.call('PEXPIRE', KEYS[1], ttlMs * 2)
for i = 4, 6 do
    if redis.call('EXISTS', KEYS[i]) == 1 then
        redis.call('PEXPIRE', KEYS[i], ttlMs)
    end
end

if ARGV[4] == '1' then
    redis.call('PEXPIRE', KEYS[3], ttlMs)
    for _, leg in ipairs(splitLegs(redis.call('HGET', KEYS[1], 'legs') or '')) do
        if leg.kind == 'PACK' then
            redis.call('PEXPIRE', ARGV[5] .. leg.id, ttlMs)
        elseif leg.kind == 'BONUS' then
            redis.call('PEXPIRE', ARGV[6] .. leg.id, ttlMs)
        end
    end
end
return 1

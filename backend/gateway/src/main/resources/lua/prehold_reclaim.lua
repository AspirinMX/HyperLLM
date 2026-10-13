-- Reclaims an expired pre-hold. The score comparison is the CAS that keeps reclaim from racing a
-- heartbeat: only a hold whose expiry has not been renewed can be released.
-- KEYS[1] hold hash, [2] holds expiry, [3] org holds set, [4] wallet, [5] pack held
-- ARGV[1] holdId, [2] nowMs
local score = redis.call('ZSCORE', KEYS[2], ARGV[1])
if not score then
    return 'STALE'
end
if tonumber(score) > tonumber(ARGV[2]) then
    return 'STALE'
end

local heldCash = tonumber(redis.call('HGET', KEYS[1], 'cash_micros') or '0') or 0
local heldBonus = tonumber(redis.call('HGET', KEYS[1], 'bonus_micros') or '0') or 0
local heldUnits = tonumber(redis.call('HGET', KEYS[1], 'pack_units') or '0') or 0

if heldCash > 0 then
    local current = tonumber(redis.call('HGET', KEYS[4], 'held_cash_micros') or '0') or 0
    redis.call('HSET', KEYS[4], 'held_cash_micros', math.max(0, current - heldCash))
end
if heldBonus > 0 then
    local current = tonumber(redis.call('HGET', KEYS[4], 'held_bonus_micros') or '0') or 0
    redis.call('HSET', KEYS[4], 'held_bonus_micros', math.max(0, current - heldBonus))
end
if heldUnits > 0 and redis.call('EXISTS', KEYS[5]) == 1 then
    local current = tonumber(redis.call('GET', KEYS[5]) or '0') or 0
    redis.call('SET', KEYS[5], math.max(0, current - heldUnits))
end

redis.call('DEL', KEYS[1])
redis.call('ZREM', KEYS[2], ARGV[1])
redis.call('SREM', KEYS[3], ARGV[1])
return 'RELEASED'

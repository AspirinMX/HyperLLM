-- KEYS[1] = gw:wallet:{orgId}
-- ARGV[1] = cash micros to deduct, ARGV[2] = bonus micros to deduct
-- Applies an already-split charge. Both buckets are clamped at zero, so a stale snapshot can
-- never produce a negative balance that the PostgreSQL CHECK would later reject.
local cashWanted = tonumber(ARGV[1]) or 0
local bonusWanted = tonumber(ARGV[2]) or 0

local bonus = tonumber(redis.call('HGET', KEYS[1], 'general_bonus_micros') or '0') or 0
local cash = tonumber(redis.call('HGET', KEYS[1], 'cash_micros') or '0') or 0

local bonusApplied = math.min(math.max(bonus, 0), math.max(bonusWanted, 0))
local cashApplied = math.min(math.max(cash, 0), math.max(cashWanted, 0))

if bonusApplied > 0 then
    redis.call('HINCRBY', KEYS[1], 'general_bonus_micros', -bonusApplied)
end
if cashApplied > 0 then
    redis.call('HINCRBY', KEYS[1], 'cash_micros', -cashApplied)
end

return {bonusApplied, cashApplied}

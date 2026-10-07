-- KEYS[1] = gw:wallet:{orgId}
-- ARGV[1] = exactCostMicros
local cost = tonumber(ARGV[1]) or 0
if cost <= 0 then
    return {0, 0}
end

local bonus = tonumber(redis.call('HGET', KEYS[1], 'general_bonus_micros') or '0') or 0
local availableBonus = math.max(bonus, 0)
local bonusDeduct = math.min(availableBonus, cost)
local cashDeduct = cost - bonusDeduct

if bonusDeduct > 0 then
    redis.call('HINCRBY', KEYS[1], 'general_bonus_micros', -bonusDeduct)
end
if cashDeduct > 0 then
    redis.call('HINCRBY', KEYS[1], 'cash_micros', -cashDeduct)
end

return {bonusDeduct, cashDeduct}

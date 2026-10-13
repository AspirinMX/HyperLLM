-- Settles a pre-hold: release the reservation, then charge the actual amounts.
-- Idempotent through the done marker, and deliberately still charges when the hold was already
-- reclaimed, so crashing a request cannot become a way to get served for free.
-- KEYS[1] wallet, [2] hold hash, [3] done marker, [4] holds expiry, [5] org holds set, [6] pack held
-- ARGV[1] holdId, [2] cashToCharge, [3] bonusToCharge, [4] packUnitsConsumed, [5] doneTtlSeconds
if redis.call('EXISTS', KEYS[3]) == 1 then
    return {'ALREADY_SETTLED'}
end

local holdExists = redis.call('EXISTS', KEYS[2])
if holdExists == 1 then
    local heldCash = tonumber(redis.call('HGET', KEYS[2], 'cash_micros') or '0') or 0
    local heldBonus = tonumber(redis.call('HGET', KEYS[2], 'bonus_micros') or '0') or 0
    local heldUnits = tonumber(redis.call('HGET', KEYS[2], 'pack_units') or '0') or 0

    if heldCash > 0 then
        local current = tonumber(redis.call('HGET', KEYS[1], 'held_cash_micros') or '0') or 0
        redis.call('HSET', KEYS[1], 'held_cash_micros', math.max(0, current - heldCash))
    end
    if heldBonus > 0 then
        local current = tonumber(redis.call('HGET', KEYS[1], 'held_bonus_micros') or '0') or 0
        redis.call('HSET', KEYS[1], 'held_bonus_micros', math.max(0, current - heldBonus))
    end
    if heldUnits > 0 and redis.call('EXISTS', KEYS[6]) == 1 then
        local current = tonumber(redis.call('GET', KEYS[6]) or '0') or 0
        redis.call('SET', KEYS[6], math.max(0, current - heldUnits))
    end

    redis.call('DEL', KEYS[2])
    redis.call('ZREM', KEYS[4], ARGV[1])
    redis.call('SREM', KEYS[5], ARGV[1])
end

local cash = tonumber(redis.call('HGET', KEYS[1], 'cash_micros') or '0') or 0
local bonus = tonumber(redis.call('HGET', KEYS[1], 'general_bonus_micros') or '0') or 0
local cashApplied = math.min(math.max(cash, 0), math.max(tonumber(ARGV[2]) or 0, 0))
local bonusApplied = math.min(math.max(bonus, 0), math.max(tonumber(ARGV[3]) or 0, 0))

if cashApplied > 0 then
    redis.call('HINCRBY', KEYS[1], 'cash_micros', -cashApplied)
end
if bonusApplied > 0 then
    redis.call('HINCRBY', KEYS[1], 'general_bonus_micros', -bonusApplied)
end

local payload = string.format('{"cash":%d,"bonus":%d,"afterReclaim":%s}',
        cashApplied, bonusApplied, tostring(holdExists == 0))
redis.call('SET', KEYS[3], payload, 'EX', tonumber(ARGV[5]))

return {'OK', cashApplied, bonusApplied, holdExists == 0 and 1 or 0}

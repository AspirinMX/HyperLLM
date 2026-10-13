-- Releases a pre-hold without charging (pre-first-byte failures, upstream errors, cancellations).
-- KEYS[1] wallet, [2] hold hash, [3] done marker, [4] holds expiry, [5] org holds set, [6] pack held
-- ARGV[1] holdId, [2] doneTtlSeconds
if redis.call('EXISTS', KEYS[3]) == 1 then
    return 'ALREADY_DONE'
end
if redis.call('EXISTS', KEYS[2]) == 1 then
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
redis.call('SET', KEYS[3], '{"released":true}', 'EX', tonumber(ARGV[2]))
return 'OK'

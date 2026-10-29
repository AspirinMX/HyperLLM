-- SQL wallet locking serializes credits with folds. The version floor fences concurrent cold loads.
-- KEYS: wallet, bonus index, new grant item, ledger position.
-- ARGV: event id, cash, bonus, expiry score, new SQL wallet version.
local wallet, index, grant, position = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
local event, cash, bonus, expiresAt, version = ARGV[1], ARGV[2], ARGV[3], ARGV[4], ARGV[5]
local function less(a, b)
    return #a < #b or (#a == #b and a < b)
end
local floor = redis.call('HGET', position, 'credit_version') or '0'
if less(floor, version) then
    redis.call('HSET', position, 'credit_version', version)
end
local marker = 'credit:' .. event
if redis.call('HEXISTS', wallet, 'cash_micros') == 0 then
    return 'COLD'
end
local loaded = redis.call('HGET', wallet, 'loaded_wallet_version') or '0'
if not less(loaded, version) or redis.call('HEXISTS', wallet, marker) == 1 then
    return 'PROJECTED'
end
if cash ~= '0' then
    redis.call('HINCRBY', wallet, 'cash_micros', cash)
else
    if not redis.call('ZSCORE', index, '__loaded_v2__') then
        return 'COLD'
    end
    if redis.call('EXISTS', grant) == 0 then
        redis.call('HSET', grant, 'remaining_micros', bonus, 'held_micros', '0',
                'expires_at', expiresAt, 'applicable_models', '*')
        local ttl = redis.call('PTTL', index)
        if ttl >= 0 then
            redis.call('PEXPIRE', grant, math.max(ttl, 1))
        end
    end
    redis.call('ZADD', index, 'NX', expiresAt, event)
end
redis.call('HSET', wallet, marker, '1')
return 'PROJECTED'

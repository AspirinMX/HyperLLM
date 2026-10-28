-- Fills a tenant's mirror from the database without ever overwriting what is already there.
--
-- Loading is only allowed to create missing keys: a wallet or batch that already exists may have
-- been debited since the read started, and rebuilding it would silently roll that debit back. The
-- same rule runs the other way, which is why a delta write is refused on a missing key; together
-- they guarantee that a rebuild and a debit can never touch the same key.
--
-- KEYS[1] wallet hash, [2] pack zset of the model, [3] bonus zset
-- ARGV[1] ttl millis
-- ARGV[2] wallet "cash|heldCash|status"
-- ARGV[3] pack count, then one "id|remaining|held|unitType|expiresAt" per pack
-- ARGV[n] bonus count, then one "id|remaining|held|expiresAt|modelScope" per grant
-- ARGV[m] pack item prefix
-- ARGV[m+1] bonus item prefix
local ttl = tonumber(ARGV[1]) or 0

-- Split on a literal '|'. A character-class repetition would also emit an empty match at every
-- delimiter, which silently shifts every field by one.
local function split(raw, maxFields)
    local parts = {}
    local start = 1
    while true do
        local pos = string.find(raw, '|', start, true)
        if not pos or (maxFields and #parts == maxFields - 1) then
            table.insert(parts, string.sub(raw, start))
            break
        end
        table.insert(parts, string.sub(raw, start, pos - 1))
        start = pos + 1
    end
    return parts
end

local function putItem(key, flat)
    if redis.call('EXISTS', key) == 1 then
        return false
    end
    local args = {}
    for i = 1, #flat, 2 do
        table.insert(args, flat[i])
        table.insert(args, flat[i + 1])
    end
    redis.call('HSET', key, unpack(args))
    redis.call('PEXPIRE', key, ttl)
    return true
end

-- Marked on the wallet, always: a control-plane write can create the hash without filling the
-- batches, and "has a wallet" must therefore not be read as "is fully mirrored".
if redis.call('EXISTS', KEYS[1]) == 0 then
    local wallet = split(ARGV[2])
    redis.call('HSET', KEYS[1],
            'cash_micros', wallet[1] or '0',
            'held_cash_micros', wallet[2] or '0',
            'status', wallet[3] or 'ACTIVE')
    redis.call('PEXPIRE', KEYS[1], ttl)
end

redis.call('HSET', KEYS[1], 'loaded', '1')

local cursor = 4
local function fill(zset, count, prefix, isBonus)
    local wasMissing = redis.call('EXISTS', zset) == 0
    for _ = 1, count do
        local entry = split(ARGV[cursor], 5)
        cursor = cursor + 1
        local id = entry[1]
        local item = prefix .. id
        if isBonus then
            local scope = entry[5] or '*'
            local existing = redis.call('HGET', item, 'remaining_micros')
            local trusted = scope ~= 'legacy' or not existing or redis.call('HEXISTS', item, 'applicable_models') == 1
                    or tonumber(existing) <= tonumber(entry[2])
            if trusted then
                redis.call('ZADD', zset, 'NX', tonumber(entry[4]), id)
                putItem(item, {'remaining_micros', entry[2], 'held_micros', entry[3], 'expires_at', entry[4]})
                redis.call('HSETNX', item, 'applicable_models', scope == 'legacy' and '*' or scope)
            end
        else
            redis.call('ZADD', zset, 'NX', tonumber(entry[5]), id)
            putItem(item, {'remaining_units', entry[2], 'held_units', entry[3],
                    'unit_type', entry[4], 'expires_at', entry[5]})
        end
    end
    redis.call('ZADD', zset, 'NX', 0, '__loaded_v2__')
    if wasMissing then
        redis.call('PEXPIRE', zset, ttl)
    end
end

fill(KEYS[2], tonumber(ARGV[3]), ARGV[#ARGV - 1], false)
local grantCount = tonumber(ARGV[cursor])
cursor = cursor + 1
fill(KEYS[3], grantCount, ARGV[#ARGV], true)
return {'OK'}

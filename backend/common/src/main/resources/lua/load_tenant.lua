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
-- ARGV[n] bonus count, then one "id|remaining|held|expiresAt" per grant
-- ARGV[m] pack item prefix
-- ARGV[m+1] bonus item prefix
local ttl = tonumber(ARGV[1]) or 0

-- Split on a literal '|'. A character-class repetition would also emit an empty match at every
-- delimiter, which silently shifts every field by one.
local function split(raw)
    local parts = {}
    local start = 1
    while true do
        local pos = string.find(raw, '|', start, true)
        if not pos then
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

if redis.call('EXISTS', KEYS[1]) == 0 then
    local wallet = split(ARGV[2])
    redis.call('HSET', KEYS[1],
            'cash_micros', wallet[1] or '0',
            'held_cash_micros', wallet[2] or '0',
            'status', wallet[3] or 'ACTIVE')
    redis.call('PEXPIRE', KEYS[1], ttl)
end

local cursor = 3
local packCount = tonumber(ARGV[cursor]) or 0
cursor = cursor + 1
if redis.call('EXISTS', KEYS[2]) == 0 then
    for _ = 1, packCount do
        local entry = split(ARGV[cursor])
        cursor = cursor + 1
        local id = entry[1]
        if id and id ~= '' then
            redis.call('ZADD', KEYS[2], tonumber(entry[5]) or 0, id)
            putItem(ARGV[#ARGV - 1] .. id, {
                'remaining_units', entry[2] or '0',
                'held_units', entry[3] or '0',
                'unit_type', entry[4] or 'TOKENS',
                'expires_at', entry[5] or '0'
            })
        end
    end
    redis.call('PEXPIRE', KEYS[2], ttl)
else
    cursor = cursor + packCount
end

local grantCount = tonumber(ARGV[cursor]) or 0
cursor = cursor + 1
if redis.call('EXISTS', KEYS[3]) == 0 then
    for _ = 1, grantCount do
        local entry = split(ARGV[cursor])
        cursor = cursor + 1
        local id = entry[1]
        if id and id ~= '' then
            redis.call('ZADD', KEYS[3], tonumber(entry[4]) or 0, id)
            putItem(ARGV[#ARGV] .. id, {
                'remaining_micros', entry[2] or '0',
                'held_micros', entry[3] or '0',
                'expires_at', entry[4] or '0'
            })
        end
    end
    redis.call('PEXPIRE', KEYS[3], ttl)
end

return {'OK'}

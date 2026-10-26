-- Reads the whole spendable state of one tenant for one model in a single round trip.
--
-- The caller allocates against "remaining minus held" so a batch that another request already
-- reserved can never be handed out twice. Expired batches are filtered here by score, which is why
-- an expired grant can never be spent even while the key is frozen without a TTL.
--
-- KEYS[1] wallet hash
-- KEYS[2] pack zset of this model
-- KEYS[3] bonus zset
-- ARGV[1] nowMs
-- ARGV[2] renew flag, '1' when this call should extend the TTLs
-- ARGV[3] ttl millis
-- ARGV[4] pack item key prefix
-- ARGV[5] bonus item key prefix
--
-- Returns a flat array of strings:
--   {'OK', cash, heldCash, status, packCount, {id, remaining, held, unitType, expiresAt} * N,
--    bonusCount, {id, remaining, held, expiresAt} * M}
-- or {'MISS_WALLET', '0', '0', ''} when the wallet has not been mirrored yet.
local MAX_LEGS = 64

if redis.call('EXISTS', KEYS[1]) == 0 then
    return {'MISS_WALLET', '0', '0', ''}
end

local now = tonumber(ARGV[1]) or 0
-- '1' renews the TTL, '2' removes it. Freezing is what keeps a key from expiring while the ledger
-- is behind: an expiring key would be rebuilt from a snapshot that trails the deductions.
local freeze = ARGV[2] == '2'
local renew = ARGV[2] == '1' or freeze
local ttl = tonumber(ARGV[3]) or 0
local packPrefix = ARGV[4]
local bonusPrefix = ARGV[5]

local out = {
    'OK',
    tostring(redis.call('HGET', KEYS[1], 'cash_micros') or '0'),
    tostring(redis.call('HGET', KEYS[1], 'held_cash_micros') or '0'),
    tostring(redis.call('HGET', KEYS[1], 'status') or '')
}

local function collect(zsetKey, prefix, fields)
    local ids = redis.call('ZRANGEBYSCORE', zsetKey, now, '+inf')
    local rows = {}
    local count = 0
    for i = 1, #ids do
        if count >= MAX_LEGS then
            break
        end
        local itemKey = prefix .. ids[i]
        local values = redis.call('HMGET', itemKey, unpack(fields))
        if values[1] then
            count = count + 1
            table.insert(rows, ids[i])
            for j = 1, #fields do
                table.insert(rows, tostring(values[j] or '0'))
            end
            if freeze then
                redis.call('PERSIST', itemKey)
            elseif renew then
                redis.call('PEXPIRE', itemKey, ttl)
            end
        end
    end
    table.insert(out, tostring(count))
    for _, value in ipairs(rows) do
        table.insert(out, value)
    end
    if freeze and redis.call('EXISTS', zsetKey) == 1 then
        redis.call('PERSIST', zsetKey)
    elseif renew and redis.call('EXISTS', zsetKey) == 1 then
        redis.call('PEXPIRE', zsetKey, ttl)
    end
end

collect(KEYS[2], packPrefix, {'remaining_units', 'held_units', 'unit_type', 'expires_at'})
collect(KEYS[3], bonusPrefix, {'remaining_micros', 'held_micros', 'expires_at'})

if freeze then
    redis.call('PERSIST', KEYS[1])
elseif renew then
    redis.call('PEXPIRE', KEYS[1], ttl)
end

-- Last element: 1 when the tenant was filled from the database, 0 when only the wallet hash exists
-- (a control-plane write, or a load that was interrupted). Trailing so the indexes above stay put.
table.insert(out, tostring(redis.call('HGET', KEYS[1], 'loaded') or '0'))
return out

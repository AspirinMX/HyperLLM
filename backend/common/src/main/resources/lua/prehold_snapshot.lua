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
-- ARGV[2] wallet/bonus renewal: 0 leave alone, 1 renew, 2 persist
-- ARGV[3] ttl millis
-- ARGV[4] pack item key prefix
-- ARGV[5] bonus item key prefix
-- ARGV[6] pack renewal, defaults to the wallet/bonus mode
-- ARGV[7] model code when checking bonus scope and index completeness
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
local orgMode, packMode = ARGV[2], ARGV[6] or ARGV[2]
local ttl = tonumber(ARGV[3]) or 0
local function touch(key, mode)
    if mode == '2' then
        redis.call('PERSIST', key)
    elseif mode == '1' then
        local remaining = redis.call('PTTL', key)
        if remaining == -1 or (remaining >= 0 and remaining < ttl) then
            redis.call('PEXPIRE', key, ttl)
        end
    end
end
local packPrefix = ARGV[4]
local bonusPrefix = ARGV[5]
local model = ARGV[7]
local function applies(item)
    if not model then
        return true
    end
    local scope = redis.call('HGET', item, 'applicable_models')
    if scope == '*' then
        return true
    end
    if not scope then
        return false
    end
    for _, applicable in ipairs(cjson.decode(scope)) do
        if applicable == model then
            return true
        end
    end
    return false
end

local out = {
    'OK',
    tostring(redis.call('HGET', KEYS[1], 'cash_micros') or '0'),
    tostring(redis.call('HGET', KEYS[1], 'held_cash_micros') or '0'),
    tostring(redis.call('HGET', KEYS[1], 'status') or '')
}

local function collect(zsetKey, prefix, fields, mode, scoped)
    local ids = redis.call('ZRANGEBYSCORE', zsetKey, now, '+inf')
    local rows = {}
    local count = 0
    for i = 1, #ids do
        if count >= MAX_LEGS then
            break
        end
        local itemKey = prefix .. ids[i]
        local values = redis.call('HMGET', itemKey, unpack(fields))
        if values[1] and (not scoped or applies(itemKey)) then
            count = count + 1
            table.insert(rows, ids[i])
            for j = 1, #fields do
                table.insert(rows, tostring(values[j] or '0'))
            end
        end
        if values[1] and (not scoped or not model or redis.call('HEXISTS', itemKey, 'applicable_models') == 1) then
            touch(itemKey, mode)
        end
    end
    table.insert(out, tostring(count))
    for _, value in ipairs(rows) do
        table.insert(out, value)
    end
    touch(zsetKey, mode)
end

collect(KEYS[2], packPrefix, {'remaining_units', 'held_units', 'unit_type', 'expires_at'}, packMode)
collect(KEYS[3], bonusPrefix, {'remaining_micros', 'held_micros', 'expires_at'}, orgMode, true)
touch(KEYS[1], orgMode)

-- Last element: 1 when the tenant was filled from the database, 0 when only the wallet hash exists
-- (a control-plane write, or a load that was interrupted). Trailing so the indexes above stay put.
local loaded = redis.call('HGET', KEYS[1], 'loaded') == '1'
if model then
    loaded = loaded and redis.call('ZSCORE', KEYS[2], '__loaded_v2__') and redis.call('ZSCORE', KEYS[3], '__loaded_v2__')
end
table.insert(out, loaded and '1' or '0')
return out

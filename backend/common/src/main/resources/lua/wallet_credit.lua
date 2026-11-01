-- Projects one authorized credit onto the live mirror, under the SQL wallet lock.
--
-- The version floor fences a cold load that read the wallet before this credit committed, and the
-- per-event marker makes a replay that follows a rolled back SQL transaction safe. The batch id is
-- the event id, so inserting a pack or bonus batch and writing its marker are the same step.
--
-- KEYS: wallet, bonus index, item (bonus/pack, or the wallet for cash), ledger position,
--       then one pack index per applicable model for a pack grant.
-- ARGV: event id, kind (CASH|BONUS|PACK), cash, bonus, expiresAt, version, model scope,
--       unit type, units.
local wallet, index, item, position = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
local event, kind = ARGV[1], ARGV[2]
local cash, bonus = ARGV[3], ARGV[4]
local expiresRaw = ARGV[5]
local expiresAt = tonumber(expiresRaw) or 0
local version, scope = ARGV[6], ARGV[7]
local unitType, units = ARGV[8], ARGV[9]

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
-- A mirror loaded at this version already contains the batch: the SQL snapshot it was built from
-- includes the committed credit.
if not less(loaded, version) or redis.call('HEXISTS', wallet, marker) == 1 then
    return 'PROJECTED'
end

local function ttlFrom(key)
    local ttl = redis.call('PTTL', key)
    return ttl
end

if kind == 'CASH' then
    redis.call('HINCRBY', wallet, 'cash_micros', cash)
elseif kind == 'BONUS' then
    -- A wallet hash without a loaded bonus index cannot accept a batch: the existing balances would
    -- be invisible and the new batch would look like the whole balance.
    if not redis.call('ZSCORE', index, '__loaded_v2__') then
        return 'COLD'
    end
    if redis.call('EXISTS', item) == 0 then
        redis.call('HSET', item, 'remaining_micros', bonus, 'held_micros', '0',
                'expires_at', expiresRaw, 'applicable_models', scope)
        local ttl = ttlFrom(index)
        if ttl >= 0 then
            redis.call('PEXPIRE', item, math.max(ttl, 1))
        end
    end
    redis.call('ZADD', index, 'NX', expiresAt, event)
elseif kind == 'PACK' then
    if redis.call('EXISTS', item) == 0 then
        redis.call('HSET', item, 'remaining_units', units, 'held_units', '0',
                'unit_type', unitType, 'expires_at', expiresRaw)
        local ttl = ttlFrom(wallet)
        if ttl >= 0 then
            redis.call('PEXPIRE', item, math.max(ttl, 1))
        end
    end
    -- A model index created here deliberately lacks the __loaded_v2__ marker: the first read will
    -- fill the other packs of that model from the database, leaving this one untouched.
    for i = 5, #KEYS do
        local created = redis.call('EXISTS', KEYS[i]) == 0
        redis.call('ZADD', KEYS[i], 'NX', expiresAt, event)
        if created then
            local ttl = ttlFrom(wallet)
            if ttl >= 0 then
                redis.call('PEXPIRE', KEYS[i], math.max(ttl, 1))
            end
        end
    end
else
    return 'INVALID_KIND'
end

redis.call('HSET', wallet, marker, '1')
return 'PROJECTED'
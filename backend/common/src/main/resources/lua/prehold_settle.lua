-- Single terminal transition for a reservation. Expiry, explicit release and normal settlement
-- contend on the same hold/done keys. The event records only deltas this invocation applied.
-- KEYS: wallet, hold, done, expiry zset, event copy, pending zset, org ledger position, event owner hash, proof
-- ARGV: holdId, nowMs, occurredAt, mode, charges, detail prefix, done TTL ms, evidence TTL ms,
--       pack prefix, bonus prefix, legacy grant id
local holdId, now, at, mode = ARGV[1], tonumber(ARGV[2]), ARGV[3], ARGV[4]
local function digits(value)
    return string.format('%.0f', value)
end

local function legs(raw)
    local result = {}
    for segment in string.gmatch(raw, '([^;]+)') do
        local kind, id, units, micros = string.match(segment, '^(%u+)|([^|]*)|(%d+)|(%d+)$')
        if not kind then
            error('invalid settlement leg')
        end
        table.insert(result, {kind = kind, id = id, units = tonumber(units), micros = tonumber(micros)})
    end
    return result
end

local function integer(value)
    return value and value >= 0 and value <= 9007199254740991 and value == math.floor(value)
end

local score = redis.call('ZSCORE', KEYS[4], holdId)
if mode == 'RECLAIM' and (not score or tonumber(score) > now) then
    return {'STALE'}
end
if redis.call('EXISTS', KEYS[3]) == 1 then
    redis.call('ZREM', KEYS[4], holdId)
    return {'ALREADY_SETTLED'}
end
local hold = redis.call('HMGET', KEYS[2], 'org_id', 'project_id', 'api_key_id', 'model_code',
        'request_id', 'served', 'legs', 'fallback_legs', 'fallback_detail', 'concurrency_keys')
if not hold[1] then
    redis.call('ZREM', KEYS[4], holdId)
    return {'MISSING_HOLD'}
end

local reclaim = mode == 'RECLAIM'
if mode == 'RELEASE' and hold[6] == '1' then
    return {'SERVED'} -- the sweep owns the floor if a response was already delivered
end
local charge = mode == 'SETTLE' or (reclaim and hold[6] == '1')
local requested = charge and legs(reclaim and (hold[8] or '') or ARGV[5]) or {}
local detail = (reclaim and (hold[9] or '') or ARGV[6])
if detail == '' then
    detail = '{"v":1,'
end

local frozen, cashHeld = {}, 0
local valid = redis.call('EXISTS', KEYS[1]) == 1
for _, leg in ipairs(legs(hold[7] or '')) do
    if leg.kind == 'CASH' then
        cashHeld = cashHeld + leg.micros
    else
        local pack = leg.kind == 'PACK'
        local key = (pack and ARGV[9] or ARGV[10]) .. leg.id
        local remainingField = pack and 'remaining_units' or 'remaining_micros'
        local heldField = pack and 'held_units' or 'held_micros'
        local amount = pack and leg.units or leg.micros
        local values = redis.call('HMGET', key, heldField, remainingField)
        local held = tonumber(values[1])
        local remaining = tonumber(values[2])
        local entryValid = integer(amount) and integer(held) and integer(remaining)
                and held >= amount and remaining >= held
        frozen[leg.kind .. leg.id] = {key = key, heldField = heldField, remainingField = remainingField,
                amount = amount, available = entryValid, charged = 0}
        valid = valid and entryValid
    end
end
local wallet = redis.call('HMGET', KEYS[1], 'cash_micros', 'held_cash_micros')
local cash, totalCashHeld = tonumber(wallet[1]), tonumber(wallet[2] or '0')
local walletValid = integer(cash) and integer(totalCashHeld) and integer(cashHeld)
        and cash >= totalCashHeld and totalCashHeld >= cashHeld
valid = valid and walletValid

local wantedCash, bonus, units, value = 0, 0, 0, 0
local applied = {}
if charge and valid then
    for _, leg in ipairs(requested) do
        if not integer(leg.units) or not integer(leg.micros) then
            return {'INVALID_CHARGE'}
        end
        if leg.kind == 'CASH' then
            wantedCash = wantedCash + leg.micros
        else
            local entry = frozen[leg.kind .. leg.id]
            if not entry then
                return {'INVALID_CHARGE'}
            end
            local amount = leg.kind == 'PACK' and leg.units or leg.micros
            entry.charged = entry.charged + amount
            if entry.charged > entry.amount then
                return {'INVALID_CHARGE'}
            end
            if amount > 0 then
                table.insert(applied, leg)
                value = value + leg.micros
                if leg.kind == 'PACK' then
                    units = units + leg.units
                else
                    bonus = bonus + leg.micros
                end
            end
        end
    end
    if not integer(wantedCash) or not integer(value + wantedCash) or not integer(units)
            or (reclaim and wantedCash > cashHeld) then
        return {'INVALID_CHARGE'}
    end
end

local cashCharge, shortfall = 0, 0
local payload = ''
if valid and charge then
    -- Unreserved cash may cover a shortfall, but cash frozen by other requests stays untouched.
    cashCharge = math.min(wantedCash, cash - (totalCashHeld - cashHeld))
    shortfall = wantedCash - cashCharge
    if cashCharge > 0 then
        table.insert(applied, {kind = 'CASH', id = '', units = 0, micros = cashCharge})
    end
    if cashCharge > 0 or bonus > 0 or units > 0 then
        local messageLegs, detailLegs = {}, {}
        for _, leg in ipairs(applied) do
            local virtual = leg.kind == 'BONUS' and leg.id == ARGV[11]
            local id = (leg.id == '' or virtual) and 'null' or cjson.encode(leg.id)
            table.insert(messageLegs, '{"type":' .. cjson.encode(leg.kind) .. ',"batchId":' .. id
                    .. ',"units":' .. digits(leg.units) .. ',"micros":' .. digits(leg.micros)
                    .. ',"virtual":' .. tostring(virtual) .. '}')
            if #detailLegs < 32 then
                table.insert(detailLegs, '{"type":' .. cjson.encode(leg.kind)
                        .. (leg.id == '' and '' or ',"id":' .. cjson.encode(leg.id))
                        .. (leg.units == 0 and '' or ',"units":' .. digits(leg.units))
                        .. ',"micros":' .. digits(leg.micros) .. '}')
            end
        end
        detail = detail .. '"legs":[' .. table.concat(detailLegs, ',') .. '],"totals":{"chargedMicros":'
                .. digits(value + cashCharge) .. ',"shortfallMicros":' .. digits(shortfall)
                .. ',"degraded":' .. tostring(reclaim) .. ',"truncatedLegs":'
                .. tostring(#applied - #detailLegs) .. '}}'
        local requestId = (hold[5] and hold[5] ~= '') and hold[5] or holdId
        payload = '{"eventId":' .. cjson.encode(holdId) .. ',"eventType":"CONSUME","occurredAt":'
                .. cjson.encode(at) .. ',"orgId":' .. cjson.encode(hold[1]) .. ',"projectId":'
                .. cjson.encode(hold[2]) .. ',"apiKeyId":' .. (hold[3] == '' and 'null' or cjson.encode(hold[3]))
                .. ',"idempotencyKey":' .. cjson.encode('req:' .. requestId)
                .. ',"cashDeltaMicros":' .. digits(-cashCharge) .. ',"bonusDeltaMicros":' .. digits(-bonus)
                .. ',"packDeltaUnits":' .. digits(-units) .. ',"shortfallMicros":' .. digits(shortfall)
                .. ',"legs":[' .. table.concat(messageLegs, ',') .. '],"allocationDetailJson":' .. cjson.encode(detail)
                .. ',"holdId":' .. cjson.encode(holdId) .. ',"settledAfterReclaim":' .. tostring(reclaim)
                .. ',"description":' .. cjson.encode('Model ' .. hold[4]) .. '}'
    end
end

-- All validation and event construction is complete. Missing mirrors never get recreated here.
for _, entry in pairs(frozen) do
    if entry.available then
        redis.call('HINCRBY', entry.key, entry.heldField, digits(-entry.amount))
        if valid and entry.charged > 0 then
            redis.call('HINCRBY', entry.key, entry.remainingField, digits(-entry.charged))
        end
    end
end
if walletValid and cashHeld > 0 then
    redis.call('HINCRBY', KEYS[1], 'held_cash_micros', digits(-cashHeld))
end
if cashCharge > 0 then
    redis.call('HINCRBY', KEYS[1], 'cash_micros', digits(-cashCharge))
end
for key in string.gmatch(hold[10] or '', '([^|]+)') do
    if tonumber(redis.call('GET', key) or '0') > 0 then
        redis.call('DECR', key)
    end
end
redis.call('DEL', KEYS[2])
redis.call('ZREM', KEYS[4], holdId)
redis.call('SET', KEYS[3], '1', 'PX', ARGV[7])
if payload ~= '' then
    local proof = {}
    local function recordGeneration(key)
        redis.call('HSETNX', key, 'ledger_generation', holdId)
        proof[key] = redis.call('HGET', key, 'ledger_generation')
    end
    recordGeneration(KEYS[1])
    for _, entry in pairs(frozen) do
        if entry.charged > 0 then
            recordGeneration(entry.key)
        end
    end
    redis.call('SET', KEYS[9], cjson.encode(proof), 'PX', ARGV[8])
    redis.call('SET', KEYS[5], payload, 'PX', ARGV[8])
    redis.call('HSET', KEYS[8], holdId, hold[1] .. '|' .. at)
    redis.call('ZADD', KEYS[6], now, holdId)
    local produced = redis.call('HGET', KEYS[7], 'produced')
    if not produced or produced < holdId then
        redis.call('HSET', KEYS[7], 'produced', holdId, 'since', at)
    end
end
return {valid and (payload ~= '' and 'SETTLED' or 'RELEASED') or 'MISS_ENTITLEMENTS',
        digits(cashCharge), digits(bonus), digits(shortfall), payload}

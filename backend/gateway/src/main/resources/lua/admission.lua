-- KEYS[1] = gw:wallet:{orgId}
-- KEYS[2] = key rpm key, KEYS[3] = project rpm key
-- KEYS[4] = key conc key, KEYS[5] = project conc key, KEYS[6] = org conc key
-- ARGV[1..5] = limits (0 = unlimited): keyRpm, projRpm, keyConc, projConc, orgConc

if redis.call('EXISTS', KEYS[1]) == 0 then
    return 'MISS_WALLET'
end

local status = redis.call('HGET', KEYS[1], 'status')
if status ~= 'ACTIVE' then
    return 'ORG_SUSPENDED'
end

local cash = tonumber(redis.call('HGET', KEYS[1], 'cash_micros') or '0') or 0
local bonus = tonumber(redis.call('HGET', KEYS[1], 'general_bonus_micros') or '0') or 0
if (cash + bonus) <= 0 then
    return 'INSUFFICIENT_BALANCE'
end

local keyRpmLimit = tonumber(ARGV[1]) or 0
local projRpmLimit = tonumber(ARGV[2]) or 0
if keyRpmLimit > 0 then
    local cur = tonumber(redis.call('GET', KEYS[2]) or '0') or 0
    if cur >= keyRpmLimit then
        return 'RATE_LIMIT'
    end
end
if projRpmLimit > 0 then
    local cur = tonumber(redis.call('GET', KEYS[3]) or '0') or 0
    if cur >= projRpmLimit then
        return 'RATE_LIMIT'
    end
end

local acquiredConc = {}
local concLimits = { tonumber(ARGV[3]) or 0, tonumber(ARGV[4]) or 0, tonumber(ARGV[5]) or 0 }
for i = 1, 3 do
    local lim = concLimits[i]
    if lim > 0 then
        local ck = KEYS[3 + i]
        local cur = redis.call('INCR', ck)
        redis.call('EXPIRE', ck, 180)
        table.insert(acquiredConc, ck)
        if cur > lim then
            for _, rollbackKey in ipairs(acquiredConc) do
                redis.call('DECR', rollbackKey)
            end
            return 'RATE_LIMIT'
        end
    end
end

if keyRpmLimit > 0 then
    local v = redis.call('INCR', KEYS[2])
    if v == 1 then
        redis.call('EXPIRE', KEYS[2], 120)
    end
end
if projRpmLimit > 0 then
    local v = redis.call('INCR', KEYS[3])
    if v == 1 then
        redis.call('EXPIRE', KEYS[3], 120)
    end
end

return 'OK'

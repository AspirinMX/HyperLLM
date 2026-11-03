-- Capacity of one upstream channel, shared by every attempt routed to it.
--
-- KEYS: window zset, request hash, token hash, concurrency counter
-- ARGV: attempt id, now ms, ttl ms, rpm limit, tpm limit, concurrency limit, estimated tokens,
--       window seconds, release flag
local window, counts, tokens, concurrency = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
local attempt, nowMs, ttlMs = ARGV[1], tonumber(ARGV[2]) or 0, tonumber(ARGV[3]) or 0
local rpmLimit, tpmLimit = tonumber(ARGV[4]) or 0, tonumber(ARGV[5]) or 0
local concurrencyLimit = tonumber(ARGV[6]) or 0
local estimated = math.max(0, tonumber(ARGV[7]) or 0)
local windowSeconds = math.max(1, math.min(300, tonumber(ARGV[8]) or 60))

if ARGV[9] == '1' then
    local current = tonumber(redis.call('GET', concurrency) or '0') or 0
    if current > 0 then
        redis.call('DECR', concurrency)
    end
    return 'RELEASED'
end

local nowSec = math.floor(nowMs / 1000)
local from = nowSec - windowSeconds + 1
local expired = redis.call('ZRANGEBYSCORE', window, '-inf', from - 1)
if #expired > 0 then
    redis.call('ZREM', window, unpack(expired))
    for _, member in ipairs(expired) do
        redis.call('HDEL', counts, member)
        redis.call('HDEL', tokens, member)
    end
end

local fields, requests, used = {}, 0, 0
for second = from, nowSec do
    fields[#fields + 1] = tostring(second)
end
if #fields > 0 then
    local countValues = redis.call('HMGET', counts, unpack(fields))
    local tokenValues = redis.call('HMGET', tokens, unpack(fields))
    for i = 1, #fields do
        requests = requests + (tonumber(countValues[i]) or 0)
        used = used + (tonumber(tokenValues[i]) or 0)
    end
end

if rpmLimit > 0 and requests + 1 > rpmLimit then
    return 'RATE_LIMIT'
end
if tpmLimit > 0 and used + estimated > tpmLimit then
    return 'RATE_LIMIT'
end
if concurrencyLimit > 0 then
    local current = redis.call('INCR', concurrency)
    if current > concurrencyLimit then
        redis.call('DECR', concurrency)
        return 'CONCURRENCY_LIMIT'
    end
end
redis.call('PEXPIRE', concurrency, ttlMs)

redis.call('ZADD', window, nowSec, nowSec)
redis.call('HINCRBY', counts, nowSec, 1)
if estimated > 0 then
    redis.call('HINCRBY', tokens, nowSec, estimated)
end
local windowTtl = (windowSeconds + 60) * 1000
redis.call('PEXPIRE', window, windowTtl)
redis.call('PEXPIRE', counts, windowTtl)
redis.call('PEXPIRE', tokens, windowTtl)
return 'OK'

-- KEYS: pending zset, owner hash, payload copy, debit proof.
-- ARGV: event id, next score or empty to resolve, org waiting set prefix.
if ARGV[2] ~= '' then
    return redis.call('ZADD', KEYS[1], 'XX', 'GT', ARGV[2], ARGV[1])
end
local owner = redis.call('HGET', KEYS[2], ARGV[1])
if owner then
    local org = string.match(owner, '^([^|]+)|')
    if org then
        redis.call('SREM', ARGV[3] .. org, ARGV[1])
    end
end
redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('HDEL', KEYS[2], ARGV[1])
return redis.call('DEL', KEYS[3], KEYS[4])

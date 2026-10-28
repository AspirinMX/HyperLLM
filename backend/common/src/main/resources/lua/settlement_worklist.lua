-- KEYS: pending zset, owner hash, payload copy, debit proof; ARGV: event id, next score or empty to resolve.
if ARGV[2] ~= '' then
    return redis.call('ZADD', KEYS[1], 'XX', 'GT', ARGV[2], ARGV[1])
end
redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('HDEL', KEYS[2], ARGV[1])
return redis.call('DEL', KEYS[3], KEYS[4])

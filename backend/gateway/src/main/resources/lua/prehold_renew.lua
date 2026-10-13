-- Renews a live pre-hold and the concurrency leases in one atomic step.
-- KEYS[1] hold hash, [2] holds expiry zset, [3]..[5] concurrency counters
-- ARGV[1] holdId, [2] new expiry epoch ms, [3] ttl seconds
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end
redis.call('ZADD', KEYS[2], tonumber(ARGV[2]), ARGV[1])
redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[3]) * 2000)
for i = 3, 5 do
    if redis.call('EXISTS', KEYS[i]) == 1 then
        redis.call('EXPIRE', KEYS[i], tonumber(ARGV[3]))
    end
end
return 1

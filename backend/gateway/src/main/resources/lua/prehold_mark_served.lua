-- Marks that the client has received the first byte of the response.
--
-- Only a served request may be settled at the prompt-only floor when its lease is reclaimed;
-- without this marker a crash mid-flight would have to guess between "the upstream ran" and
-- "nothing happened".
--
-- KEYS[1] hold
-- ARGV[1] ttl millis
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end
redis.call('HSET', KEYS[1], 'served', '1')
redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[1]) * 2)
return 1

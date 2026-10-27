-- Marks that the client has received the first byte of the response.
--
-- Only a served request may be settled at the prompt-only floor when its lease is reclaimed;
-- without this marker a crash mid-flight would have to guess between "the upstream ran" and
-- "nothing happened".
--
-- KEYS[1] hold
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end
redis.call('HSET', KEYS[1], 'served', '1')
return 1

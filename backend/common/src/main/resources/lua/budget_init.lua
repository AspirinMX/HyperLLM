-- Installs one project month's budget authority from the confirmed fold.
--
-- Only a missing key may be installed. A key that already exists may have been charged since the
-- read started, and replacing it would roll that charge back; the caller treats EXISTS as proof
-- that another instance won the race. The state comes from PostgreSQL so an unverified month stays
-- unverified here and keeps a hard-capped project closed.
--
-- KEYS[1] budget hash
-- ARGV[1] month, [2] confirmed micros, [3] state, [4] hard cap micros, [5] soft cap micros,
--       [6] budget start ISO timestamp, [7] ttl millis
if redis.call('EXISTS', KEYS[1]) == 1 then
    return 'EXISTS'
end
redis.call('HSET', KEYS[1],
        'month', ARGV[1],
        'used_micros', ARGV[2],
        'held_micros', '0',
        'hard_cap_micros', ARGV[4],
        'soft_cap_micros', ARGV[5],
        'state', ARGV[3],
        'start_at', ARGV[6],
        'loaded', '1',
        'generation', '1')
redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[7]))
return 'OK'

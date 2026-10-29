-- KEYS: org waiting set, ledger position. ARGV: event ID, SQL wallet version being committed.
if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 0 then
    return 0
end
local floor = redis.call('HGET', KEYS[2], 'debit_version') or '0'
local version = ARGV[2]
if #floor < #version or (#floor == #version and floor < version) then
    redis.call('HSET', KEYS[2], 'debit_version', version)
end
return 1

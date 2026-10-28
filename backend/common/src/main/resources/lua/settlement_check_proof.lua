-- A recreated mirror might have been loaded from PG before this debit was folded. Such a mirror
-- cannot support an ADJUST. Check all affected keys in one operation and give the SQL fold time to commit.
local raw = redis.call('GET', KEYS[1])
if not raw then return 0 end
local proof = cjson.decode(raw)
for key, generation in pairs(proof) do
    if redis.call('HGET', key, 'ledger_generation') ~= generation then
        return 0
    end
end
for key, _ in pairs(proof) do
    redis.call('PEXPIRE', key, 30000, 'GT')
end
return 1

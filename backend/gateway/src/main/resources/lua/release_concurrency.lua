for i = 1, #KEYS do
    local k = KEYS[i]
    if redis.call('EXISTS', k) == 1 then
        local v = redis.call('DECR', k)
        if v < 0 then
            redis.call('SET', k, '0', 'EX', 180)
        end
    end
end
return 1

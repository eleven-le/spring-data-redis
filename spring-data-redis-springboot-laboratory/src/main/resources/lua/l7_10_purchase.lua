local tokenValue = redis.call('GET', KEYS[1])
if not tokenValue or tokenValue ~= ARGV[1] then
    return 1
end

if redis.call('EXISTS', KEYS[2]) == 1 then
    return 2
end

local stock = tonumber(redis.call('GET', KEYS[3]) or '-1')
if stock <= 0 then
    return 3
end

redis.call('DECR', KEYS[3])
redis.call('DEL', KEYS[1])
redis.call('SET', KEYS[2], ARGV[2], 'EX', tonumber(ARGV[3]))
return 0

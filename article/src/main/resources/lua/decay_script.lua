local key = KEYS[1]
local factor = tonumber(ARGV[1])
local maxCap = tonumber(ARGV[2])

local elements = redis.call('ZRANGE', key, 0, -1, 'WITHSCORES')
if #elements == 0 then
    return 0
end

for i = 1, #elements, 2 do
    local member = elements[i]
    local score = tonumber(elements[i + 1])
    local newScore = score * factor
    if newScore < 1.0 then
        redis.call('ZREM', key, member)
    else
        redis.call('ZADD', key, newScore, member)
    end
end

local total = redis.call('ZCARD', key)
if total > maxCap then
    redis.call('ZREMRANGEBYRANK', key, 0, total - maxCap - 1)
end
return 1
---@diagnostic disable: undefined-global
local key = KEYS[1]
local code = ARGV[1]
local lockTime = tonumber(ARGV[2])

if redis.call('EXISTS', key) == 0 then return -1 end

local storedCode = redis.call('HGET', key, 'code')
local maxAttemptsStr = redis.call('HGET', key, 'maxAttempts')
local attemptsStr = redis.call('HGET', key, 'attempts')

local maxAttempts = tonumber(maxAttemptsStr) or 5
local attempts = tonumber(attemptsStr) or 0

if attempts >= maxAttempts then return -2 end

if storedCode == code then
    redis.call('DEL', key)
    return 1
else
    attempts = attempts + 1
    redis.call('HSET', key, 'attempts', tostring(attempts))
    if attempts >= maxAttempts then
        redis.call('EXPIRE', key, lockTime)
        return -2
    end
    return 0
end

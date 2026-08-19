local aggKey = KEYS[1]
local field = ARGV[1]
local delta = tonumber(ARGV[2])

-- 扣减 Hash 暂存桶中的增量
local v = redis.call('HINCRBY', aggKey, field, -delta)

-- 若扣减后计数 <= 0，清理该字段
if v == 0 then
    redis.call('HDEL', aggKey, field)
end

return v

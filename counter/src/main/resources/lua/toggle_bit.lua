-- 4KB 分片位图原子翻转状态，仅当状态从未置位->置位（或反之）发生实际变化时返回 1，否则返回 0
local bmKey = KEYS[1]
local offset = tonumber(ARGV[1])
local op = ARGV[2] -- 'add' 或 'remove'

local prev = redis.call('GETBIT', bmKey, offset)

if op == 'add' then
    if prev == 1 then
        return 0
    end
    redis.call('SETBIT', bmKey, offset, 1)
    return 1
elseif op == 'remove' then
    if prev == 0 then
        return 0
    end
    redis.call('SETBIT', bmKey, offset, 0)
    return 1
end

return -1

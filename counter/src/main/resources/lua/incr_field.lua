local sdsKey = KEYS[1]
local schemaLen = tonumber(ARGV[1])
local fieldSize = tonumber(ARGV[2])
local idx = tonumber(ARGV[3])
local delta = tonumber(ARGV[4])

-- 大端序读取 32 位无符号整数
local function read32be(s, off)
    local b = {string.byte(s, off + 1, off + 4)}
    local n = 0
    for i = 1, 4 do
        n = n * 256 + b[i]
    end
    return n
end

-- 大端序将 32 位整数编码为 4 字节二进制字符
local function write32be(n)
    local t = {}
    for i = 4, 1, -1 do
        t[i] = n % 256
        n = math.floor(n / 256)
    end
    return string.char(t[1], t[2], t[3], t[4])
end

local cnt = redis.call('GET', sdsKey)
if not cnt then
    cnt = string.rep(string.char(0), schemaLen * fieldSize)
end

local off = idx * fieldSize
local v = read32be(cnt, off) + delta
if v < 0 then
    v = 0
end

local seg = write32be(v)
cnt = string.sub(cnt, 1, off) .. seg .. string.sub(cnt, off + fieldSize + 1)
redis.call('SET', sdsKey, cnt)
return 1
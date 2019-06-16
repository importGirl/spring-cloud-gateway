local tokens_key = KEYS[1]
local timestamp_key = KEYS[2]
--redis.log(redis.LOG_WARNING, "tokens_key " .. tokens_key)
-- 每秒请求次数
local rate = tonumber(ARGV[1])
-- 令牌痛容量
local capacity = tonumber(ARGV[2])
-- 当前时间戳
local now = tonumber(ARGV[3])
-- 每次消耗令牌数量
local requested = tonumber(ARGV[4])

-- 计算令牌桶满需要多久时间 单位：秒
local fill_time = capacity/rate
-- 超时时间；保证时间充足；超过这个时间 key会过期，为什么呢？超过该时间令牌桶都生产满了， 直接过期，下次进来直接设置令牌桶最大值就好啦！
local ttl = math.floor(fill_time*2)

--redis.log(redis.LOG_WARNING, "rate " .. ARGV[1])
--redis.log(redis.LOG_WARNING, "capacity " .. ARGV[2])
--redis.log(redis.LOG_WARNING, "now " .. ARGV[3])
--redis.log(redis.LOG_WARNING, "requested " .. ARGV[4])
--redis.log(redis.LOG_WARNING, "filltime " .. fill_time)
--redis.log(redis.LOG_WARNING, "ttl " .. ttl)
-- 获得令牌桶剩余数量
local last_tokens = tonumber(redis.call("get", tokens_key))
-- 初始化
if last_tokens == nil then
  last_tokens = capacity
end
--redis.log(redis.LOG_WARNING, "last_tokens " .. last_tokens)
-- 令牌痛最后令牌填充时间
local last_refreshed = tonumber(redis.call("get", timestamp_key))
-- 初始化
if last_refreshed == nil then
  last_refreshed = 0
end
--redis.log(redis.LOG_WARNING, "last_refreshed " .. last_refreshed)
-- 与上一次生产令牌的间隔
local delta = math.max(0, now-last_refreshed)
-- 计算令牌痛剩余数量
local filled_tokens = math.min(capacity, last_tokens+(delta*rate))
-- 剩余数量 >= 请求令牌数量
local allowed = filled_tokens >= requested
-- 新令牌数
local new_tokens = filled_tokens
-- 是否获得令牌
local allowed_num = 0
-- 可以拿到令牌则计算剩余令牌数
if allowed then
  new_tokens = filled_tokens - requested
  allowed_num = 1
end

--redis.log(redis.LOG_WARNING, "delta " .. delta)
--redis.log(redis.LOG_WARNING, "filled_tokens " .. filled_tokens)
--redis.log(redis.LOG_WARNING, "allowed_num " .. allowed_num)
--redis.log(redis.LOG_WARNING, "new_tokens " .. new_tokens)

-- 重新设置 令牌
redis.call("setex", tokens_key, ttl, new_tokens)
redis.call("setex", timestamp_key, ttl, now)

return { allowed_num, new_tokens }

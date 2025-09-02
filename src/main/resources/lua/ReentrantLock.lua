local key = KEYS[1];
local threadId = ARGV[1];
local releaseTime = ARGV[2];

if (redis.call('exists', key) == 0) then
    -- 不存在，获取锁
    redis.call('hset', key, threadId, 1);
    -- 设置有效期
    redis.call('expire', key, releaseTime);
    return 1;
end

-- 存在，判断是否是自己的锁
if (redis.call('hexists', key, threadId) == 1) then
    -- 是自己的锁，重入次数+1
    redis.call('hincrby', key, threadId, 1);
    -- 设置有效期
    redis.call('expire', key, releaseTime);
    return 1;
end
return 0;   -- 锁不是自己的，获取锁失败

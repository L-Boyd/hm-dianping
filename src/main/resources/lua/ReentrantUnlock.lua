local key = KEYS[1];
local threadId = ARGV[1];
local releaseTime = ARGV[2];

-- 判断当前锁是否还是被自己持有
if (redis.call('hexists', key, threadId) == 0) then
    -- 已经不是自己的，直接返回
    return nil;
end

-- 是自己的锁，重入次数-1
local count = redis.call('hincrby', key, threadId, -1);

-- 如果重入次数为0，删除锁
if (count > 0) then
    -- 重新设置有效期
    redis.call('expire', key, releaseTime);
    return nil;
else
    -- 删除锁
    redis.call('del', key);
    return nil;
end
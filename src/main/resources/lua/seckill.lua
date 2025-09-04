local voucherId = ARGV[1]
local userId = ARGV[2]

local storeKey = 'seckill:stock:' .. voucherId -- lua的..相当于java的+
local orderId = 'seckill:order:' .. voucherId

local store = tonumber(redis.call('get', storeKey))
if (store <= 0) then
    -- 库存不足返回1
    return 1
end

-- SISMEMBER orderKey userId查看用户是否下过单
if (redis.call('sismember', orderId, userId) == 1) then
    -- 存在，说明重复下单，返回2
    return 2
end

-- 扣减库存
redis.call('incrby', storeKey, -1)

-- 下单
redis.call('sadd', orderId, userId)
return 0;

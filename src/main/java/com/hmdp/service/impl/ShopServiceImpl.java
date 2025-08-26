package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        //return queryWithPassThrough(id);
        // 用工具类
//        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        if (shop == null) {
//            return Result.fail("商铺不存在");
//        }
//        return Result.ok(shop);

        // 互斥锁解决缓存击穿
        //return queryWithMutex(id);

        // 逻辑过期解决缓存击穿
        //return queryWithLogicalExpire(id);
        // 用工具类
        Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }

    // 解决缓存穿透
    public Result queryWithPassThrough(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        if (shopJson != null) {
            return Result.fail("商铺不存在");
        }
        // 不存在，根据id查询数据库
        Shop shop = getById(id);
        // 判断是否存在
        if (shop == null) {
            // 不存在，redis存空值，返回错误
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("商铺不存在");
        }
        // 存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 返回
        return Result.ok(shop);
    }

    public Result queryWithMutex(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        // 不存在的结果存的是""
        if (shopJson != null) {
            return Result.fail("商铺不存在");
        }
        // 不存在，根据id查询数据库
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)) {
            try {
                Shop shop = getById(id);

                // 模拟重建延时
                Thread.sleep(1000);

                // 判断是否存在
                if (shop == null) {
                    // 不存在，redis存空值，返回错误
                    stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return Result.fail("商铺不存在");
                }
                // 存在，写入redis
                stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
                // 返回
                return Result.ok(shop);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                unlock(lockKey);
            }
        }
        else {
            try {
                Thread.sleep(50);
            }
            catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return queryWithMutex(id);
        }
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空");
        }
        // 写入数据库
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Result queryWithLogicalExpire(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 从redis查询商铺缓存
        String dataJson = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isBlank(dataJson)) {
            return Result.fail("商铺不存在");
        }
        // 存在
        RedisData  data = JSONUtil.toBean(dataJson, RedisData.class);
        JSONObject shopObj = (JSONObject) data.getData();
        Shop shop = JSONUtil.toBean(shopObj, Shop.class);
        if (data.getExpireTime().isAfter(LocalDateTime.now())) {
            // 未过期直接返回
            return Result.ok(shop);
        } else {
            // 数据过期，尝试获取锁，开启新线程重建缓存，返回过期数据
            try {
                boolean isLock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
                if (isLock) {
                    // double check，防止重复重建
                    data = JSONUtil.toBean(dataJson, RedisData.class);
                    shop = JSONUtil.toBean((JSONObject) data.getData(), Shop.class);
                    if (data.getExpireTime().isAfter(LocalDateTime.now())) {
                        return Result.ok(shop);
                    }

                    // 开启一个新线程重建缓存
                    CACHE_REBUILD_EXECUTOR.submit(() -> {
                       saveShop2Redis(id, RedisConstants.CACHE_SHOP_TTL);
                    });
                    return Result.ok(shop);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                unlock(RedisConstants.LOCK_SHOP_KEY + id);
            }
        }
        return Result.ok(shop);
    }

    public void saveShop2Redis(Long id, Long expireMinutes) {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(expireMinutes));
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
}

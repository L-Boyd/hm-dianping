package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 缓存工具类
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate redisTemplate, StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 存数据，有过期时间
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    // 存数据，有逻辑过期时间
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 解决了缓存穿透的查询
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        // 从redis查询缓存
        String dataJson = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isNotBlank(dataJson)) {
            // 存在，直接返回
            R r = JSONUtil.toBean(dataJson, type);
            return r;
        }
        // 如果不是null前面还没返回，说明获取到的是缓存的空字符串
        if (dataJson != null) {
            return null;
        }
        // 不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 判断是否存在
        if (r == null) {
            // 不存在，redis存空值，返回错误
            this.set(key, "", time, timeUnit);
            return null;
        }
        // 存在，写入redis
        this.set(key, JSONUtil.toJsonStr(r), time, timeUnit);
        // 返回
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 用逻辑过期解决了缓存击穿的查询
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        // 从redis查询商铺缓存
        String dataJson = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isBlank(dataJson)) {
            return null;
        }
        // 存在
        RedisData data = JSONUtil.toBean(dataJson, RedisData.class);
        JSONObject obj = (JSONObject) data.getData();
        R r = JSONUtil.toBean(obj, type);
        if (data.getExpireTime().isAfter(LocalDateTime.now())) {
            // 未过期直接返回
            return r;
        } else {
            // 数据过期，尝试获取锁，开启新线程重建缓存，返回过期数据
            String lockKey = key + ":rebuild";
            try {
                boolean isLock = tryLock(lockKey);
                if (isLock) {
                    // double check，防止重复重建
                    data = JSONUtil.toBean(dataJson, RedisData.class);
                    r = JSONUtil.toBean((JSONObject) data.getData(), type);
                    if (data.getExpireTime().isAfter(LocalDateTime.now())) {
                        return r;
                    }

                    // 开启一个新线程重建缓存
                    CACHE_REBUILD_EXECUTOR.submit(() -> {
                        R r1 = dbFallback.apply(id);
                        this.setWithLogicalExpire(key, r1, time, timeUnit);
                    });
                    return r;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                unlock(lockKey);
            }
            return r;
        }
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 100, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}

package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 基于redis的id生成器
 */
@Component
public class RedisIdWorker {

    // 开始时间戳，2003-9-19-1-2-3
    private static final long BEGIN_TIMESTAMP = 1063933323L;

    // 序列号位数
    private static final long COUNT_BITS = 32;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 返回64位id，第0位是符号位，第1-31位是时间戳，第32-64位是序列号
     */
    public long nextId(String keyPrefix) {
        // 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;

        // 生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        return timeStamp << COUNT_BITS |  count;
    }

    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2003, 9, 19, 1, 2, 3);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);
    }
}

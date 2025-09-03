package com.hmdp.config;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest
class RedissonTest {

    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RedissonClient redissonClient2;
    @Resource
    private RedissonClient redissonClient3;

    private RLock lock;

    @BeforeEach
    void setUp() {
        RLock lock1 = redissonClient.getLock("order");
        RLock lock2 = redissonClient2.getLock("order");
        RLock lock3 = redissonClient3.getLock("order");

        // 创建联锁 multiLock
        lock = redissonClient.getMultiLock(lock1, lock2, lock3);
    }

    @Test
    void method1() throws InterruptedException {
        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
        if (!isLock) {
            log.error("获取锁失败1");
            return;
        }
        try {
            log.info("获取锁成功1");
            method2();
            log.info("开始执行业务1");
        } finally {
            log.warn("准备释放锁1");
            lock.unlock();
        }
    }

    void method2() throws InterruptedException {
        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
        if (!isLock) {
            log.error("获取锁失败2");
            return;
        }
        try {
            log.info("获取锁成功2");
            log.info("开始执行业务2");
        } finally {
            log.warn("准备释放锁2");
            lock.unlock();
        }
    }
}
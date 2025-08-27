package com.hmdp.utils;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RedisIdWorkerTest {

    @Autowired
    private RedisIdWorker redisIdWorker;

    private ExecutorService executorService = Executors.newFixedThreadPool(500);

    @Test
    void testNextId() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable runnable = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("test");
                System.out.println(id);
            }
            latch.countDown();
        };
        long start = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            executorService.submit(runnable);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("耗时：" + (end - start));
    }

    @Test
    void testNextId2() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            long id = redisIdWorker.nextId("test");
            System.out.println(id);
        }
    }
}
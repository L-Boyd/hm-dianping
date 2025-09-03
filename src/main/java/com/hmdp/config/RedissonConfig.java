package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Value("${spring.redis.host}")
    private String redisHost;

    @Value("${spring.redis.port}")
    private String redisPort;

    @Value("${spring.redis.password}")
    private String redisPassword;

    @Bean
    public RedissonClient redissonClient() {
        String redisAddress = "redis://" + redisHost + ":" + redisPort;

        Config config = new Config();
        config.useSingleServer()
                .setAddress(redisAddress)
                .setPassword(redisPassword);
        return Redisson.create(config);

    }

    @Bean
    public RedissonClient redissonClient2() {
        String redisAddress = "redis://192.168.1.7:6379";

        Config config = new Config();
        config.useSingleServer()
                .setAddress(redisAddress)
                .setPassword(redisPassword);
        return Redisson.create(config);

    }

    @Bean
    public RedissonClient redissonClient3() {
        String redisAddress = "redis://192.168.1.7:6400";

        Config config = new Config();
        config.useSingleServer()
                .setAddress(redisAddress)
                .setPassword(redisPassword);
        return Redisson.create(config);

    }
}

package com.yupi.yuaiagent.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置类 —— 读 application.yml 的 redisson.* 配置
 */
@Configuration
public class RedissonConfig {

    @Value("${redisson.single-server-config.address:redis://127.0.0.1:6379}")
    private String address;

    @Value("${redisson.single-server-config.database:0}")
    private int database;

    @Value("${redisson.single-server-config.connection-minimum-idle-size:5}")
    private int connectionMinIdleSize;

    @Value("${redisson.single-server-config.connection-pool-size:10}")
    private int connectionPoolSize;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(address)
                .setDatabase(database)
                .setPassword(null)
                .setConnectionMinimumIdleSize(connectionMinIdleSize)
                .setConnectionPoolSize(connectionPoolSize)
                .setTimeout(5000)
                .setRetryAttempts(3)
                .setRetryInterval(1500);
        return Redisson.create(config);
    }
}

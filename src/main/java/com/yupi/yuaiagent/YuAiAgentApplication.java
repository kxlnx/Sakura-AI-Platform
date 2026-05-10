package com.yupi.yuaiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = {
        // 当前使用 Redis 作为唯一存储，排除数据库自动配置
        DataSourceAutoConfiguration.class
})
//@MapperScan("com.yupi.yuaiagent.mapper") // 使用 MySQL 存储时取消注释
public class YuAiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(YuAiAgentApplication.class, args);
    }

}

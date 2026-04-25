package com.yupi.yuaiagent.memory.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Milvus REST API 配置
 * 用于执行混合搜索（稠密向量 + BM25）
 */
@Slf4j
@Configuration
public class MilvusRestConfig {

    @Value("${spring.ai.vectorstore.milvus.client.host:localhost}")
    private String host;

    @Value("${spring.ai.vectorstore.milvus.client.port:19530}")
    private int port;

    @Bean
    public RestTemplate milvusRestTemplate() {
        return new RestTemplate();
    }

    public String getMilvusUri() {
        // Milvus REST API 地址
        return "http://" + host + ":" + (port + 14); // HTTP 端口 = gRPC 端口 + 14
    }
}

package com.yupi.yuaiagent.memory;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 长期记忆配置类
 */
@Configuration
public class LongTermMemoryConfig {

    /**
     * 长期记忆写入器
     */
    @Bean
    public LongTermMemoryWriter longTermMemoryWriter(VectorStore vectorStore) {
        return new LongTermMemoryWriter(vectorStore);
    }

    /**
     * 长期记忆读取器
     */
    @Bean
    public LongTermMemoryReader longTermMemoryReader(VectorStore vectorStore) {
        return new LongTermMemoryReader(vectorStore);
    }

    /**
     * 长期记忆增强顾问
     */
    @Bean
    public LongTermMemoryAdvisor longTermMemoryAdvisor() {
        return new LongTermMemoryAdvisor();
    }
}

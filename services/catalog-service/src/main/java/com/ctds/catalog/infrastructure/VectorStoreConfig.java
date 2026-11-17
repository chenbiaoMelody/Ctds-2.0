package com.ctds.catalog.infrastructure;

import com.ctds.common.vectorstore.EmbeddingProvider;
import com.ctds.common.vectorstore.HashingEmbeddingProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 语义检索向量化 Bean 装配（WBS-3.10.2 hifi §1；零配置键，维度为常量）。 */
@Configuration
public class VectorStoreConfig {

    @Bean
    public EmbeddingProvider embeddingProvider() {
        return new HashingEmbeddingProvider();
    }
}

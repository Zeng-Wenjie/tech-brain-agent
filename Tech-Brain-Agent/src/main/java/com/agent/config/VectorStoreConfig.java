package com.agent.config;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15.BgeSmallZhV15EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.redis.RedisEmbeddingStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/*
* 配置langchain4j组件
 */
@Configuration
public class VectorStoreConfig {
    @Bean// 将BGE模型注入到spring容器中
    public EmbeddingModel embeddingModel() {
        return new BgeSmallZhV15EmbeddingModel();
    }
    //注册Redis向量库连接注册为Bean
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return RedisEmbeddingStore.builder()
                .host("127.0.0.1")
                .port(6379)
                .dimension(512) // 纯本地运行，不联网，不扣费，没404
                .build();
    }
}

package com.agent.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15.BgeSmallZhV15EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class VectorStoreConfig {

    @Value("${milvus.host:localhost}")
    private String milvusHost; // Milvus 服务地址，应用通过它连接向量数据库。

    @Value("${milvus.port:19530}")
    private Integer milvusPort; // Milvus 端口，默认 19530。

    @Value("${milvus.collection-name:article_vector}")
    private String collectionName; // collection 类似表名，article_vector 专门存文章向量。

    @Value("${milvus.dimension:512}")
    private Integer dimension; // 向量维度必须和 BgeSmallZhV15EmbeddingModel 输出维度一致，否则写入/检索会失败。

    @Bean
    public EmbeddingModel embeddingModel() {
        return new BgeSmallZhV15EmbeddingModel(); // 中文 embedding 模型，负责把文章和问题映射到同一语义空间。
    }

    @Bean
    @Lazy // 惰性创建：MilvusEmbeddingStore.build() 会同步连接 Milvus，推迟到首次使用时再连，避免后端启动时干等 Milvus 就绪（冷启动曾因此卡约 128s）。
    public EmbeddingStore<TextSegment> embeddingStore() {
        return MilvusEmbeddingStore.builder() // 初始化 LangChain4j 的 Milvus 向量库封装，供 RAG 检索和向量同步复用。
                .host(milvusHost)
                .port(milvusPort)
                .collectionName(collectionName) // 指定集合后，文章向量会集中写入该 Milvus collection。
                .dimension(dimension) // collection 初始化和检索都依赖该维度配置。
                .build();
    }
}

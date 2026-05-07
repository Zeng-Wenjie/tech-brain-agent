package com.agent;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15.BgeSmallZhV15EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.redis.RedisEmbeddingStore;

public class GenerateTextFromTextInput {
    public static void main(String[] args) {
        // 1. 初始化本地中文向量模型（纯本地运行，不联网，不扣费，没404）
        // 1. Initialize the local Chinese embedding model.
        EmbeddingModel embeddingModel = new BgeSmallZhV15EmbeddingModel();

        // 2. 连接你的 Docker Redis Stack
        // 2. Connect to your Docker Redis Stack instance.
        EmbeddingStore<TextSegment> embeddingStore = RedisEmbeddingStore.builder()
                .host("127.0.0.1")
                .port(6379)
                .dimension(512) // 强制注意：BGE-small-zh 模型的维度是 512 / BGE-small-zh uses 512 dimensions.
                .build();

        // 3. 准备一条测试文本
        // 3. Prepare a test text.
        System.out.println("正在本地利用 CPU 生成向量...");
        TextSegment segment = TextSegment.from("Tech-Brain 项目的向量化测试，确保 RAG 链路打通。");

        // 4. 执行生成并存入 Redis
        // 4. Generate the embedding and store it in Redis.
        Embedding embedding = embeddingModel.embed(segment).content();
        embeddingStore.add(embedding, segment);

        System.out.println("--------------------------------------------------");
        System.out.println("测试成功！向量已成功生成并存入 Redis 库。");
        System.out.println("你可以放心推进后续的业务代码开发了。");
        System.out.println("--------------------------------------------------");
    }
}

package com.agent;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15.BgeSmallZhV15EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;

public class GenerateTextFromTextInput {
    public static void main(String[] args) {
        EmbeddingModel embeddingModel = new BgeSmallZhV15EmbeddingModel();

        EmbeddingStore<TextSegment> embeddingStore = MilvusEmbeddingStore.builder()
                .host("localhost")
                .port(19530)
                .collectionName("article_vector")
                .dimension(512)
                .build();

        TextSegment segment = TextSegment.from("Tech-Brain project vectorization test for the RAG flow.");
        Embedding embedding = embeddingModel.embed(segment).content();
        embeddingStore.add(embedding, segment);

        System.out.println("Vector generated and stored in Milvus.");
    }
}

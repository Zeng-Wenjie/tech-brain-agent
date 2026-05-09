package com.agent.service.impl;

import com.agent.entity.Article;
import com.agent.mapper.AgentMapper;
import com.agent.service.ArticleVectorService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Collection;

@Slf4j
@Service
public class ArticleVectorServiceImpl implements ArticleVectorService {

    private static final int VECTOR_STATUS_SUCCESS = 1;
    private static final int VECTOR_STATUS_FAILED = 2;
    private static final int VECTOR_ERROR_MAX_LENGTH = 500;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private AgentMapper articleMapper;

    @Override
    public void syncArticleVector(Article article) {
        if (article == null || article.getId() == null) {
            log.warn("Skip article vector sync because article or id is null: {}", article);
            return;
        }

        Long articleId = article.getId();
        String vectorId = buildVectorId(articleId);
        String text = buildVectorText(article);

        try {
            TextSegment segment = TextSegment.from(text, buildMetadata(article));
            Embedding embedding = embeddingModel.embed(text).content();

            embeddingStore.remove(vectorId);
            addVector(vectorId, embedding, segment);

            updateVectorStatus(articleId, vectorId, VECTOR_STATUS_SUCCESS, null);
            log.info("Article vector synced to Milvus, articleId={}, vectorId={}", articleId, vectorId);
        } catch (Exception e) {
            String error = limitError(e.getMessage());
            updateVectorStatus(articleId, vectorId, VECTOR_STATUS_FAILED, error);
            log.error("Article vector sync to Milvus failed, articleId={}, vectorId={}", articleId, vectorId, e);
        }
    }

    @Override
    public void deleteArticleVector(Long articleId) {
        if (articleId == null) {
            return;
        }

        String vectorId = buildVectorId(articleId);
        try {
            embeddingStore.remove(vectorId);
            log.info("Article vector deleted from Milvus, articleId={}, vectorId={}", articleId, vectorId);
        } catch (Exception e) {
            log.error("Article vector delete from Milvus failed, articleId={}, vectorId={}", articleId, vectorId, e);
        }
    }

    @Override
    public void deleteArticleVectorBatch(Collection<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return;
        }

        articleIds.forEach(this::deleteArticleVector);
    }

    private void updateVectorStatus(Long articleId, String vectorId, Integer vectorStatus, String vectorError) {
        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, articleId)
                .set(Article::getVectorId, vectorId)
                .set(Article::getVectorStatus, vectorStatus)
                .set(Article::getVectorError, vectorError));
    }

    private void addVector(String vectorId, Embedding embedding, TextSegment segment) throws Exception {
        try {
            Method addWithIdAndEmbedded = embeddingStore.getClass().getMethod(
                    "add",
                    String.class,
                    Embedding.class,
                    Object.class);
            addWithIdAndEmbedded.invoke(embeddingStore, vectorId, embedding, segment);
            return;
        } catch (NoSuchMethodException ignored) {
            // LangChain4j 0.36.x keeps the public EmbeddingStore API narrower than some implementations.
        }

        Method addInternal = embeddingStore.getClass().getDeclaredMethod(
                "addInternal",
                String.class,
                Embedding.class,
                TextSegment.class);
        addInternal.setAccessible(true);
        addInternal.invoke(embeddingStore, vectorId, embedding, segment);
    }

    private String buildVectorText(Article article) {
        String title = StringUtils.hasText(article.getTitle()) ? article.getTitle() : "";
        String content = StringUtils.hasText(article.getContent()) ? article.getContent() : "";
        return title + "\n" + content;
    }

    private Metadata buildMetadata(Article article) {
        Metadata metadata = new Metadata();
        metadata.put("articleId", article.getId());
        metadata.put("vectorId", buildVectorId(article.getId()));
        metadata.put("userId", String.valueOf(article.getUserId()));
        metadata.put("title", StringUtils.hasText(article.getTitle()) ? article.getTitle() : "");
        metadata.put("content", StringUtils.hasText(article.getContent()) ? article.getContent() : "");
        return metadata;
    }

    private String buildVectorId(Long articleId) {
        return "article:" + articleId;
    }

    private String limitError(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > VECTOR_ERROR_MAX_LENGTH ? message.substring(0, VECTOR_ERROR_MAX_LENGTH) : message;
    }
}

package com.agent.service.impl;

import com.agent.entity.Article;
import com.agent.mapper.AgentMapper;
import com.agent.service.ArticleVectorService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
/*
    本地数据库与向量库之间的双写一致同步
 */
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
            log.warn("跳过文章向量同步，文章为空或ID为空: {}", article);
            return;
        }

        Long articleId = article.getId();
        String vectorId = buildVectorId(articleId); // 固定vectorId，确保一篇笔记只对应一个向量。
        String text = buildVectorText(article); // 标题和正文一起入向量，提高检索语义完整性。

        try {
            TextSegment segment = TextSegment.from(text);
            Embedding embedding = embeddingModel.embed(text).content(); // 调用本地Embedding模型生成文本向量。

            try {
                embeddingStore.remove(vectorId); // 写入前先删旧向量，避免笔记修改后旧内容残留。
            } catch (Exception e) {
                log.warn("重建文章向量前删除旧向量失败，articleId={}, vectorId={}", articleId, vectorId, e);
            }

            addVector(vectorId, embedding, segment); // 写入Redis向量库，并保留TextSegment供RAG检索返回上下文。
            updateVectorStatus(articleId, vectorId, VECTOR_STATUS_SUCCESS, null); // vectorStatus=1，表示向量同步成功。
            log.info("文章向量同步成功，articleId={}, vectorId={}", articleId, vectorId);
        } catch (Exception e) {
            String error = limitError(e.getMessage());
            updateVectorStatus(articleId, vectorId, VECTOR_STATUS_FAILED, error); // vectorStatus=2，主业务已成功但向量失败，记录状态便于后续重试。
            // 这里不向外抛异常，Redis向量同步失败不能影响MySQL主业务结果。
            log.error("文章向量同步失败，articleId={}, vectorId={}", articleId, vectorId, e);
        }
    }

    @Override
    public void deleteArticleVector(Long articleId) {
        if (articleId == null) {
            return;
        }

        String vectorId = buildVectorId(articleId); // MySQL笔记删除后，同步删除Redis对应向量，避免RAG检索到已删除内容。
        try {
            embeddingStore.remove(vectorId);
            log.info("文章向量删除成功，articleId={}, vectorId={}", articleId, vectorId);
        } catch (Exception e) {
            log.error("文章向量删除失败，articleId={}, vectorId={}", articleId, vectorId, e);
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
        Method addInternal = embeddingStore.getClass().getDeclaredMethod(
                "addInternal",
                String.class,
                Embedding.class,
                TextSegment.class);
        addInternal.setAccessible(true);
        addInternal.invoke(embeddingStore, vectorId, embedding, segment);
    }

    private String buildVectorText(Article article) {
        // 拼接标题和内容，让向量同时覆盖主题和正文信息。
        String title = StringUtils.hasText(article.getTitle()) ? article.getTitle() : "";
        String content = StringUtils.hasText(article.getContent()) ? article.getContent() : "";
        return title + "\n" + content;
    }

    private String buildVectorId(Long articleId) {
        // 固定格式article:{id}，保证同一笔记更新时覆盖同一个向量位置。
        return "article:" + articleId;
    }

    private String limitError(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > VECTOR_ERROR_MAX_LENGTH ? message.substring(0, VECTOR_ERROR_MAX_LENGTH) : message;
    }
}

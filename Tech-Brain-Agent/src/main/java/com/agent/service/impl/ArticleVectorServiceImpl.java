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

    private static final int VECTOR_STATUS_SUCCESS = 1; // 向量同步成功，Milvus 中已有可检索索引。
    private static final int VECTOR_STATUS_FAILED = 2; // 向量同步失败，MySQL 主数据仍然保留，后续可重试重建。
    private static final int VECTOR_ERROR_MAX_LENGTH = 500;

    @Autowired
    private EmbeddingModel embeddingModel; // 文章标题和内容先转 embedding，再写入 Milvus。

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore; // Milvus 是可重建的向量索引，不作为文章主数据源。

    @Autowired
    private AgentMapper articleMapper; // MySQL 保存文章主数据和向量同步状态。

    @Override
    public void syncArticleVector(Article article) { // 新增/修改文章后同步向量，让后续 RAG 可以检索到这篇笔记。
        if (article == null || article.getId() == null) {
            log.warn("Skip article vector sync because article or id is null");
            return;
        }

        Long articleId = article.getId();
        String vectorId = buildVectorId(articleId); // 固定 ID 让同一篇文章重复同步时可以覆盖旧向量。
        String text = buildVectorText(article); // 标题和正文一起参与 embedding，提升按标题或正文提问时的召回。

        try {
            TextSegment segment = TextSegment.from(text, buildMetadata(article)); // metadata 用于 RAG 还原标题、正文和用户过滤条件。
            Embedding embedding = embeddingModel.embed(text).content(); // embedding 维度必须和 Milvus collection dimension 配置一致。

            embeddingStore.remove(vectorId); // 先删再加等价于 upsert，避免旧内容残留在 Milvus。
            addVector(vectorId, embedding, segment); // 使用稳定 vectorId 写入，便于后续删除和重建。

            updateVectorStatus(articleId, vectorId, VECTOR_STATUS_SUCCESS, null);
            log.info("Article vector synced to Milvus, articleId={}, vectorId={}", articleId, vectorId);
        } catch (Exception e) {
            String error = limitError(e.getMessage()); // 失败原因截断后写 MySQL，避免错误堆栈撑爆字段。
            updateVectorStatus(articleId, vectorId, VECTOR_STATUS_FAILED, error);
            log.error("Article vector sync to Milvus failed, articleId={}, vectorId={}", articleId, vectorId, e);
        }
    }

    @Override
    public void deleteArticleVector(Long articleId) { // 删除文章时清理 Milvus，避免已删除笔记继续被 RAG 命中。
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
    public void deleteArticleVectorBatch(Collection<Long> articleIds) { // 批量删除逐条清理向量，保持 MySQL 和 Milvus 索引最终一致。
        if (articleIds == null || articleIds.isEmpty()) {
            return;
        }

        articleIds.forEach(this::deleteArticleVector);
    }

    private void updateVectorStatus(Long articleId, String vectorId, Integer vectorStatus, String vectorError) { // 只更新向量状态字段，不影响文章正文主数据。
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
            addWithIdAndEmbedded.invoke(embeddingStore, vectorId, embedding, segment); // 优先使用实现类公开的带 ID 写入方法。
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
        addInternal.invoke(embeddingStore, vectorId, embedding, segment); // 兼容当前 LangChain4j 版本中未暴露到接口的方法。
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
        return "article:" + articleId; // vectorId 加业务前缀，避免未来多类型向量共用 collection 时 ID 冲突。
    }

    private String limitError(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > VECTOR_ERROR_MAX_LENGTH ? message.substring(0, VECTOR_ERROR_MAX_LENGTH) : message;
    }
}

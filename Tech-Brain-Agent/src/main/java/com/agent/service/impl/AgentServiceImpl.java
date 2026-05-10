package com.agent.service.impl;

import com.agent.constant.GenerateContant;
import com.agent.entity.Article;
import com.agent.entity.dto.ArticleSaveDTO;
import com.agent.event.ArticleVectorSyncEvent;
import com.agent.mapper.AgentMapper;
import com.agent.service.AgentService;
import com.agent.service.PromptService;
import com.agent.utils.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AgentServiceImpl extends ServiceImpl<AgentMapper, Article> implements AgentService {

    @Autowired
    private AgentMapper articleMapper;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private GoogleAiGeminiChatModel model;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PromptService promptService;

    @Override
    public String chat(String msg) {
        Long currentUserId = UserContext.getUserId();
        Embedding embedding = embeddingModel.embed(msg).content();

        // 保持原有Milvus检索逻辑，只替换Prompt构建方式。
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(embedding)
                .maxResults(3)
                .minScore(0.7)
                .filter(MetadataFilterBuilder.metadataKey("userId").isEqualTo(String.valueOf(currentUserId)))
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        List<EmbeddingMatch<TextSegment>> relatedEmbeddings = searchResult.matches();
        log.info("Milvus article_vector search hits={}, userId={}", relatedEmbeddings.size(), currentUserId);

        String context = relatedEmbeddings.stream()
                .map(match -> buildContext(match.embedded()))
                .collect(Collectors.joining("\n\n"));

        String prompt = promptService.buildChatPrompt(context, msg);
        return model.generate(prompt);
    }

    private String buildContext(TextSegment segment) {
        String title = segment.metadata().getString("title");
        String content = segment.metadata().getString("content");
        if (content != null && !content.isBlank()) {
            return ((title != null && !title.isBlank()) ? title + "\n" : "") + content;
        }
        return segment.text();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveAiNote(ArticleSaveDTO dto) {
        Article article = new Article();
        BeanUtils.copyProperties(dto, article);

        Long currentUserId = UserContext.getUserId();
        article.setUserId(currentUserId);
        article.setSourceType(GenerateContant.ARTICLE_SOURCE_TYPE_AI);
        article.setCreateTime(LocalDateTime.now());
        article.setUpdateTime(LocalDateTime.now());
        article.setVectorStatus(0);
        articleMapper.insert(article);

        // 笔记保存后仍通过事务事件同步向量，不改变原有链路。
        eventPublisher.publishEvent(new ArticleVectorSyncEvent(
                ArticleVectorSyncEvent.EventType.UPSERT,
                article,
                null));

        log.info("保存笔记成功:{}", article);
    }

    @Override
    public String summarizeArticle(Long articleId) {
        Long currentUserId = UserContext.getUserId();
        Article article = articleMapper.selectOne(new LambdaQueryWrapper<Article>()
                .eq(Article::getId, articleId)
                .eq(Article::getUserId, currentUserId));

        if (article == null) {
            throw new RuntimeException("笔记不存在或无权限访问");
        }

        String prompt = promptService.buildArticleSummaryPrompt(article.getTitle(), article.getContent());
        return model.generate(prompt);
    }
}

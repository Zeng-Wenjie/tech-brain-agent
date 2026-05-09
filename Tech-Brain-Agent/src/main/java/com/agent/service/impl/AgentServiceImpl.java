package com.agent.service.impl;
import com.agent.service.AgentService;
import com.agent.constant.GenerateContant;
import com.agent.entity.Article;
import com.agent.entity.dto.ArticleSaveDTO;
import com.agent.event.ArticleVectorSyncEvent;
import com.agent.mapper.AgentMapper;
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
    private EmbeddingModel embeddingModel;//注入向量模型 / Inject the embedding model.
    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;
    @Autowired
    private GoogleAiGeminiChatModel model;
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    public String chat(String msg) {
        Long currentUserId = UserContext.getUserId();
        Embedding embedding = embeddingModel.embed(msg).content();

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

        String prompt = "你是一个智能技术助手。请优先基于以下参考资料回答用户问题。\n" +
                "参考资料：\n" + context + "\n\n" +
                "如果参考资料不足以完全解答，或者可以提供更有价值的扩展技术背景，请结合你的专业知识进行适当补充。\n" +
                "用户问题：" + msg;
        // 发送消息并获取回复
        // Send the prompt and get the model response.
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
    @Transactional(rollbackFor = Exception.class) // 先保证MySQL保存处于事务内，向量同步由监听器在提交后执行。
    public void saveAiNote(ArticleSaveDTO dto) {
        // 保存笔记
        // Save the note.
        Article article= new Article();

        BeanUtils.copyProperties(dto,article);//拷贝属性 / Copy properties.
        //在ThreadLocal 中获取当前用户ID
        // Get the current user ID from ThreadLocal.
        Long currentUserId = UserContext.getUserId();
        article.setUserId(currentUserId);

        // 设定 sourceType 为 1，代表来源是 AI 生成（根据你自己的表设计规范调整）
        // Set sourceType to 1 to indicate AI-generated content.
        article.setSourceType(GenerateContant.ARTICLE_SOURCE_TYPE_AI);
        article.setCreateTime(LocalDateTime.now());
        article.setUpdateTime(LocalDateTime.now());
        article.setVectorStatus(0); // 先标记为未同步，MySQL保存成功后再通过事件同步Milvus向量库。
        articleMapper.insert(article);

        eventPublisher.publishEvent(new ArticleVectorSyncEvent( // 只发布同步事件，不在保存笔记的主流程里直接操作Redis。
                ArticleVectorSyncEvent.EventType.UPSERT, // 新增和更新都按UPSERT处理，最终重建同一个固定向量。
                article,
                null));


        log.info("保存笔记成功:{}",article);
    }
    @Override
    public String summarizeArticle(Long articleId) {
        Long currentUserId = UserContext.getUserId();
        Article article = articleMapper.selectOne(new LambdaQueryWrapper<Article>()
                .eq(Article::getId, articleId)
                .eq(Article::getUserId, currentUserId)); // 只查当前登录用户自己的笔记，避免越权总结别人的内容。

        if (article == null) {
            throw new RuntimeException("笔记不存在或无权限访问");
        }

        String prompt = """
        你是一个技术笔记总结助手。

        请根据下面的笔记内容生成总结。

        要求：
        1. 使用中文。
        2. 返回纯文本。
        3. 不要使用 Markdown。
        4. 不要出现 ```、##、**、\\n 等格式符号。
        5. 先用一句话总结主题。
        6. 再列出 3-5 个核心要点。

        笔记标题：
        %s

        笔记内容：
        %s
        """.formatted(article.getTitle(), article.getContent());

        return model.generate(prompt);
    }
}

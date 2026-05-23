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
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
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

/**
 * AI问答与AI笔记业务实现。
 *
 * <p>当前正式聊天入口已经统一为POST /chat/message，本类不再承载旧同步/chat问答和旧主动拼Prompt链路。</p>
 * <p>Tool Calling真实RAG检索入口仍复用searchRagContents(query)，调用链为：RagSearchTool -> AgentService.searchRagContents(query) -> Milvus。</p>
 * <p>保存AI笔记和文章总结逻辑继续留在本类，保存笔记时仍先写MySQL再发布向量同步事件，不改变原有Milvus同步链路。</p>
 */
@Slf4j
@Service
public class AgentServiceImpl extends ServiceImpl<AgentMapper, Article> implements AgentService {

    @Autowired
    private AgentMapper articleMapper;

    @Autowired
    private EmbeddingModel embeddingModel; // 将用户问题转成向量，供 Milvus 做语义相似度检索。

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore; // Milvus 向量库入口，存放文章向量和用于 RAG 的元数据。

    @Autowired
    private ChatLanguageModel chatLanguageModel; // 同步大模型，用于文章总结这类一次性完整生成。

    @Autowired
    private ApplicationEventPublisher eventPublisher; // 保存笔记后发布事件，让向量同步在事务提交后衔接。

    @Autowired
    private PromptService promptService; // 统一构造 Prompt，避免 RAG、纯聊天、总结模板散落在业务代码中。

    @Override
    public List<String> searchRagContents(String query) { // Tool Calling 专用：只做现有 Milvus 检索，不调用大模型。
        Long currentUserId = UserContext.getUserId(); // 沿用原 RAG 的用户隔离策略，避免检索到其他用户笔记。
        Embedding embedding = embeddingModel.embed(query).content(); // 将工具 query 转成向量，和文章向量在 Milvus 中做相似度匹配。

        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(embedding) // 使用 query embedding 作为 Milvus 检索向量。
                .maxResults(3) // 沿用原 RAG topK=3，控制返回给 Tool Calling 的上下文长度。
                .minScore(0.7) // 沿用原相似度阈值，避免低相关内容进入工具结果。
                .filter(MetadataFilterBuilder.metadataKey("userId").isEqualTo(String.valueOf(currentUserId))) // 继续按 userId 过滤私有知识库。
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest); // 通过现有 EmbeddingStore 实际检索 Milvus。
        List<EmbeddingMatch<TextSegment>> relatedEmbeddings = searchResult.matches(); // 取出 Milvus 返回的相似片段。
        int hitCount = relatedEmbeddings == null ? 0 : relatedEmbeddings.size(); // 统计命中数量，便于日志排查。
        log.info("Milvus article_vector tool search hits={}, userId={}", hitCount, currentUserId); // 复用同一 collection 的检索日志。

        if (relatedEmbeddings == null || relatedEmbeddings.isEmpty()) { // 无命中时返回空集合，由 ToolChat 统一生成无结果文案。
            return List.of();
        }

        return relatedEmbeddings.stream()
                .map(match -> buildContext(match.embedded())) // 复用原有 context 构造逻辑，从 metadata/text 中还原知识片段。
                .filter(context -> context != null && !context.isBlank()) // 过滤空内容，避免空片段干扰模型回答。
                .collect(Collectors.toList());
    }

    private String buildContext(TextSegment segment) {
        String title = segment.metadata().getString("title"); // 优先使用同步向量时写入的标题，回答时更容易定位来源。
        String content = segment.metadata().getString("content"); // content 来自文章正文，是 RAG 主要知识内容。
        if (content != null && !content.isBlank()) {
            return ((title != null && !title.isBlank()) ? title + "\n" : "") + content;
        }
        return segment.text();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveAiNote(ArticleSaveDTO dto) { // 事务包住 MySQL 写入，保证笔记主数据先稳定落库。
        Article article = new Article();
        BeanUtils.copyProperties(dto, article);

        Long currentUserId = UserContext.getUserId();
        article.setUserId(currentUserId);
        article.setSourceType(GenerateContant.ARTICLE_SOURCE_TYPE_AI);
        article.setCreateTime(LocalDateTime.now());
        article.setUpdateTime(LocalDateTime.now());
        article.setVectorStatus(0); // 0 表示向量尚未同步，后续事件监听器会更新为成功或失败。
        articleMapper.insert(article);

        // 笔记保存后仍通过事务事件同步向量，不改变原有链路。
        eventPublisher.publishEvent(new ArticleVectorSyncEvent(
                ArticleVectorSyncEvent.EventType.UPSERT,
                article,
                null)); // 先写 MySQL 再发事务事件，Milvus 失败不会破坏主业务数据。

        log.info("保存笔记成功:{}", article);
    }

    @Override
    public String summarizeArticle(Long articleId) {
        Long currentUserId = UserContext.getUserId();
        Article article = articleMapper.selectOne(new LambdaQueryWrapper<Article>()
                .eq(Article::getId, articleId)
                .eq(Article::getUserId, currentUserId)); // articleId 必须叠加 userId 查询，防止用 ID 越权总结别人的笔记。

        if (article == null) {
            throw new RuntimeException("笔记不存在或无权限访问");
        }

        String prompt = promptService.buildArticleSummaryPrompt(article.getTitle(), article.getContent()); // 总结使用专用 Prompt，和聊天/RAG 的回答规则隔离。
        return chatLanguageModel.generate(prompt);
    }
}

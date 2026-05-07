package com.agent.service;
import com.agent.AgentService;
import com.agent.constant.GenerateContant;
import com.agent.entity.Article;
import com.agent.entity.dto.ArticleSaveDTO;
import com.agent.mapper.AgentMapper;
import com.agent.utils.UserContext;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
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
    private EmbeddingModel embeddingModel;//注入向量模型
    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;//注入Redis向量库
    @Autowired
    private GoogleAiGeminiChatModel model;

    @Override
    public String chat(String msg) {
        //RAG编写：
        // 将用户的提问转为向量
        Embedding embedding = embeddingModel.embed(msg).content();
        //构建查询参数
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(embedding)
                .maxResults(3)//最大返回条数为3
                .minScore(0.4)//最低相似度阔值为0.7
                .build();//构建查询参数

        //去Redis向量库进行相似度搜索
        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        List<EmbeddingMatch<TextSegment>> relatedEmbeddings = searchResult.matches();
        // 确认是不是查出来为 0
        log.info("=== 核心排查：从 Redis 中检索到的数据条数：{} ===", relatedEmbeddings.size());

        String context = relatedEmbeddings.stream()
                .map(match -> match.embedded().text())
                .collect(Collectors.joining("\n\n"));

        String prompt = "你是一个智能技术助手。请优先基于以下参考资料回答用户问题。\n" +
                "参考资料：\n" + context + "\n\n" +
                "如果参考资料不足以完全解答，或者可以提供更有价值的扩展技术背景，请结合你的专业知识进行适当补充。\n" +
                "用户问题：" + msg;
        // 发送消息并获取回复
        return model.generate(prompt);
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveAiNote(ArticleSaveDTO dto) {
        // 保存笔记
        Article article= new Article();
//        article.setTitle(dto.getTitle());
//        article.setContent(dto.getContent());
//        article.setTags(dto.getTags());

        BeanUtils.copyProperties(dto,article);//拷贝属性
        //根据用户ID新增
        //在ThreadLocal 中获取当前用户ID
        Long currentUserId = UserContext.getUserId();
        article.setUserId(currentUserId);

        // 设定 sourceType 为 1，代表来源是 AI 生成（根据你自己的表设计规范调整）
        article.setSourceType(GenerateContant.ARTICLE_SOURCE_TYPE_AI);
        article.setCreateTime(LocalDateTime.now());
        article.setUpdateTime(LocalDateTime.now());
        articleMapper.insert(article);

        // 将前端传来的 AI 回答内容转化为文本片段
         TextSegment segment= TextSegment.from(dto.getContent());
         //调用BGE模型，提取512个维度的向量
         Embedding embedding = embeddingModel.embed(segment).content();
         //存入Redis向量空间
         embeddingStore.add(embedding, segment);


        log.info("保存笔记成功:{}",article);
    }
}

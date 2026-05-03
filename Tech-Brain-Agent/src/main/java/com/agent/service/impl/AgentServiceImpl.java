package com.agent.service.impl;
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
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
public class AgentServiceImpl extends ServiceImpl<AgentMapper, Article> implements AgentService {

    @Autowired
    private AgentMapper articleMapper;
    @Autowired
    private EmbeddingModel embeddingModel;//注入向量模型
    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;//注入Redis向量库


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

package com.agent.service;

import com.agent.entity.Article;

import java.util.Collection;

public interface ArticleVectorService {

    void syncArticleVector(Article article); // 同步或重建单篇笔记向量，用于新增和修改后的索引刷新。

    void deleteArticleVector(Long articleId); // 删除单篇笔记对应向量，避免检索索引残留旧数据。

    void deleteArticleVectorBatch(Collection<Long> articleIds); // 批量删除多篇笔记向量，匹配批量删除笔记场景。
}

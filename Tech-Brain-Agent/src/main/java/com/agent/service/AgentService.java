package com.agent.service;
import com.agent.entity.Article;
import com.agent.entity.dto.ArticleSaveDTO;
import com.baomidou.mybatisplus.extension.service.IService;


public interface AgentService extends IService<Article> {

    String chat(String msg);

    void saveAiNote(ArticleSaveDTO dto);

    String summarizeArticle(Long articleId);
}

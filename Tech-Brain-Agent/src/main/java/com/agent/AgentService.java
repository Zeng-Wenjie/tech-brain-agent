package com.agent;
import com.agent.entity.Article;
import com.agent.entity.dto.ArticleSaveDTO;
import com.baomidou.mybatisplus.extension.service.IService;


public interface AgentService extends IService<Article> {

    void saveAiNote(ArticleSaveDTO dto);
}

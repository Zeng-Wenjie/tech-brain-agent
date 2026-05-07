package com.agent;
import com.agent.entity.Article;
import com.agent.entity.PageQuery;
import com.agent.entity.dto.PageDTO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;


public interface NotesService extends IService<Article> {

    PageDTO<Article> page(PageQuery pageQuery);

    void deleteArticleById(Long id);

    void deleteArticleBatch(List<Long> ids);

    Article updateArticle(Article article);
}

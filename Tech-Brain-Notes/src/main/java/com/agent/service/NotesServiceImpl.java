package com.agent.service;
import com.agent.NotesService;
import com.agent.entity.Article;
import com.agent.entity.PageQuery;
import com.agent.entity.dto.PageDTO;
import com.agent.event.ArticleVectorSyncEvent;
import com.agent.mapper.NotesMapper;
import com.agent.utils.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;


@Service
public class NotesServiceImpl extends ServiceImpl<NotesMapper, Article> implements NotesService {

    @Autowired
    private NotesMapper articleMapper;
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    public PageDTO<Article> page(PageQuery pageQuery) {

        // 1. 提取参数，构造 MP 官方的分页对象
        // 1. Extract parameters and build the official MyBatis-Plus page object.
        Page<Article> page = new Page<>(pageQuery.getPageNo(), pageQuery.getPageSize());

        // 2. 构造查询条件
        // 2. Build query conditions.
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<>();

        //在ThreadLocal 中获取当前用户ID
        // Get the current user ID from ThreadLocal.
        Long currentUserId = UserContext.getUserId();
        wrapper.eq(Article::getUserId, currentUserId);

        // 3. 添加排序, 默认按更新时间降序
        // 3. Add sorting, defaulting to update time in descending order.
        wrapper.orderByDesc(Article::getUpdateTime);

        // 4. 执行分页查询 (把官方的 page 传进去)
        // 4. Execute the paginated query with the MyBatis-Plus page object.
        articleMapper.selectPage(page, wrapper);

        // 5. 封装为你自己写的 PageDTO，返回给前端
        // 5. Wrap the result in PageDTO for the frontend.
        PageDTO<Article> pageDTO = new PageDTO<>();
        pageDTO.setTotal(page.getTotal());
        pageDTO.setPages(page.getPages());
        pageDTO.setList(page.getRecords()); // MP 查出来的数据叫 records / MyBatis-Plus stores query rows in records.

        return pageDTO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class) // 事件在事务内发布，真正同步由AFTER_COMMIT监听器在提交后执行。
    public void deleteArticleById(Long id) {
        //在ThreadLocal 中获取当前用户ID
        // Get the current user ID from ThreadLocal.
        Long currentUserId = UserContext.getUserId();
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(Article::getId,id).eq(Article::getUserId,currentUserId);
        int deleted = articleMapper.delete(wrapper);
        if (deleted > 0) {
            eventPublisher.publishEvent(new ArticleVectorSyncEvent( // MySQL删除成功后再发事件，清理Redis向量避免RAG命中已删除笔记。
                    ArticleVectorSyncEvent.EventType.DELETE,
                    null,
                    List.of(id)));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class) // 批量删除和事件发布处于同一事务，回滚时不会触发向量清理。
    public void deleteArticleBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        //在ThreadLocal 中获取当前用户ID
        // Get the current user ID from ThreadLocal.
        Long currentUserId = UserContext.getUserId();
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(Article::getId,ids).eq(Article::getUserId,currentUserId);
        List<Long> articleIds = articleMapper.selectList(wrapper).stream()
                .map(Article::getId)
                .collect(Collectors.toList());
        if (articleIds.isEmpty()) {
            return;
        }
        articleMapper.delete(wrapper);
        eventPublisher.publishEvent(new ArticleVectorSyncEvent( // 批量删除MySQL笔记后，批量清理Redis中的对应向量数据。
                ArticleVectorSyncEvent.EventType.BATCH_DELETE,
                null,
                articleIds));
    }

    @Override
    @Transactional(rollbackFor = Exception.class) // 修改成功提交后再同步向量，避免MySQL回滚但Redis已更新。
    public Article updateArticle(Article article) {
        int updated = articleMapper.updateById(article);
        if (updated <= 0) {
            return article;
        }

        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, article.getId())
                .set(Article::getVectorStatus, 0)
                .set(Article::getVectorError, null));

        Article latestArticle = articleMapper.selectById(article.getId()); // 读取数据库最新内容，用于重建Redis向量。
        eventPublisher.publishEvent(new ArticleVectorSyncEvent( // 修改后重建向量，避免RAG继续检索到旧内容。
                ArticleVectorSyncEvent.EventType.UPSERT,
                latestArticle,
                null));
        return latestArticle;
    }

}

package com.agent.service;
import com.agent.NotesService;
import com.agent.entity.Article;
import com.agent.entity.PageQuery;
import com.agent.entity.dto.PageDTO;
import com.agent.mapper.NotesMapper;
import com.agent.utils.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class NotesServiceImpl extends ServiceImpl<NotesMapper, Article> implements NotesService {

    @Autowired
    private NotesMapper articleMapper;

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
    public void deleteArticleById(Long id) {
        //在ThreadLocal 中获取当前用户ID
        // Get the current user ID from ThreadLocal.
        Long currentUserId = UserContext.getUserId();
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(Article::getId,id).eq(Article::getUserId,currentUserId);
        articleMapper.delete(wrapper);
    }

    @Override
    public void deleteArticleBatch(List<Long> ids) {
        //在ThreadLocal 中获取当前用户ID
        // Get the current user ID from ThreadLocal.
        Long currentUserId = UserContext.getUserId();
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(Article::getId,ids).eq(Article::getUserId,currentUserId);
        articleMapper.delete(wrapper);
    }

    @Override
    public Article updateArticle(Article article) {
        articleMapper.updateById(article);
        return article;
    }

}

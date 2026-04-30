package com.agent.controller;

import com.agent.aopanno.Log;
import com.agent.entity.Article;
import com.agent.entity.PageDTO;
import com.agent.entity.PageQuery;
import com.agent.entity.Result;
import com.agent.mapper.NotesMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/article")
public class NotesController {
    @Autowired
    private NotesMapper articleMapper;

    //分页查询操作
    @Operation(summary = "根据用户ID分页查询笔记")
    @GetMapping("/page")
    public Result<PageDTO<Article>> page(PageQuery pageQuery) {

        // 1. 提取参数，构造 MP 官方的分页对象
        Page<Article> page = new Page<>(pageQuery.getPageNo(), pageQuery.getPageSize());

        // 2. 构造查询条件
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<>();
        // TODO: 目前登录模块还没做。等 JWT 做完后，改从 ThreadLocal 拿
        Long currentUserId = 1L;
        wrapper.eq(Article::getUserId, currentUserId);

        // 3. 添加排序, 默认按更新时间降序
        wrapper.orderByDesc(Article::getUpdateTime);

        // 4. 执行分页查询 (把官方的 page 传进去)
        articleMapper.selectPage(page, wrapper);

        // 5. 封装为你自己写的 PageDTO，返回给前端
        PageDTO<Article> pageDTO = new PageDTO<>();
        pageDTO.setTotal(page.getTotal());
        pageDTO.setPages(page.getPages());
        pageDTO.setList(page.getRecords()); // MP 查出来的数据叫 records

        return Result.success(pageDTO);
    }

//单删
    @Log
    @DeleteMapping("/{id}")
    public Result deleteArticle(@PathVariable Long id) {
        log.info("删除成功:{}" ,id);
        // TODO: 目前登录模块还没做。等 JWT 做完后，把这行删掉，改成从 ThreadLocal 里拿真实 ID
        Long currentUserId = 1L;
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(Article::getId,id).eq(Article::getUserId,currentUserId);
        articleMapper.delete(wrapper);
        return Result.success("删除成功");
    }

    //批量删除
    @Operation(summary = "批量删除")
    @DeleteMapping("/batch")
    public Result deleteArticle(@RequestBody List<Long> ids) {
        log.info("批量删除成功:{}" ,ids);
        // TODO: 目前登录模块还没做。等 JWT 做完后，把这行删掉，改成从 ThreadLocal 里拿真实 ID
        Long currentUserId = 1L;
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Article::getId,ids).eq(Article::getUserId,currentUserId);
        articleMapper.delete(wrapper);
        return Result.success("批量删除成功");
    }


    @Log
    @PutMapping
    public Result updateArticle(@RequestBody Article article) {
        log.info("修改成功:{}" ,article);
        articleMapper.updateById(article);
        return Result.success(article);
    }
}

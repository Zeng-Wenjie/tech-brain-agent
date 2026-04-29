package com.agent.controller;

import com.agent.aopanno.Log;
import com.agent.entity.Article;
import com.agent.entity.Result;
import com.agent.mapper.NotesMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/article")
public class NotesController {
    @Autowired
    private NotesMapper articleMapper;

    //这里进行分页查询操作
    @GetMapping
    public Result getArticle() {
        log.info("查询成功");
        List<Article> articleList= articleMapper.selectList(null);
        return Result.success(articleList);
    }


//    @Log
//    @DeleteMapping("/{id}")
//    public Result deleteArticle(@PathVariable Long id) {
//        log.info("删除成功:{}" ,id);
//        articleMapper.deleteById(id);
//        return Result.success("删除成功");
//    }

    //批量删除
    @Operation(summary = "批量删除")
    @DeleteMapping("/batch")
    public Result deleteArticle(@RequestBody List<Long> ids) {
        Long currentUserId = 1L;
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(Article::getId,ids).eq(Article::getUserId,currentUserId);
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

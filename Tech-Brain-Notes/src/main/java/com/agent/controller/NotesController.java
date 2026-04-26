package com.agent.controller;

import com.agent.aopanno.Log;
import com.agent.entity.Article;
import com.agent.entity.Result;
import com.agent.mapper.NotesMapper;
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

    @Log
    @GetMapping
    public Result getArticle() {
        log.info("查询成功");
        List<Article> articleList= articleMapper.selectList(null);
        return Result.success(articleList);
    }

    @Log
    @PostMapping
    public Result addArticle(@RequestBody Article article) {
        log.info("添加成功:{}" ,article);
        article.setSourceType(2);
        article.setCreateTime(LocalDateTime.now());
        article.setUpdateTime(LocalDateTime.now());
        articleMapper.insert(article);
        return Result.success(article);
    }

    @Log
    @DeleteMapping("/{id}")
    public Result deleteArticle(@PathVariable Long id) {
        log.info("删除成功:{}" ,id);
        articleMapper.deleteById(id);
        return Result.success("删除成功");
    }

    @Log
    @PutMapping
    public Result updateArticle(@RequestBody Article article) {
        log.info("修改成功:{}" ,article);
        articleMapper.updateById(article);
        return Result.success(article);
    }
}

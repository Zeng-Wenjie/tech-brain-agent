package com.agent.controller;

import com.agent.NotesService;
import com.agent.aopanno.Log;
import com.agent.entity.Article;
import com.agent.entity.PageQuery;
import com.agent.entity.Result;
import com.agent.entity.dto.PageDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/article")
@Tag(name = "笔记管理接口")
public class NotesController {
    @Autowired
    private NotesService notesService;

    //分页查询操作
    // Paginated query.
    @Operation(summary = "根据用户ID分页查询笔记")
    @GetMapping("/page")
    public Result<PageDTO<Article>> page(PageQuery pageQuery) {
        return Result.success(notesService.page(pageQuery));
    }

//单删
// Delete a single article.
    @Log
    @Operation(summary = "根据ID删除笔记")
    @DeleteMapping("/{id}")
    public Result deleteArticle(@PathVariable Long id) {
        log.info("删除成功:{}", id);
        notesService.deleteArticleById(id);
        return Result.success("删除成功");
    }

    //批量删除
    // Delete articles in batch.
    @Operation(summary = "批量删除笔记")
    @DeleteMapping("/batch")
    public Result deleteArticle(@RequestBody List<Long> ids) {
        log.info("批量删除成功:{}", ids);
        notesService.deleteArticleBatch(ids);
        return Result.success("批量删除成功");
    }

    @Log
    @Operation(summary = "修改笔记")
    @PutMapping
    public Result updateArticle(@RequestBody Article article) {
        log.info("修改成功:{}", article);
        Article updatedArticle = notesService.updateArticle(article);
        return Result.success(updatedArticle);
    }
}

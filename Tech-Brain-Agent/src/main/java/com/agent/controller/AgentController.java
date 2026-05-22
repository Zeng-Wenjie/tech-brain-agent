package com.agent.controller;

import com.agent.service.AgentService;
import com.agent.aopanno.Log;
import com.agent.entity.Result;
import com.agent.entity.dto.ArticleSaveDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AI问答与笔记保存接口")
@Slf4j
@RestController
public class AgentController {
    @Autowired
    private AgentService agentService; // Controller 只编排请求入口，核心 Tool Calling/RAG/笔记逻辑交给 Service。

//    @Operation(summary = "智能RAG问答")
//    @GetMapping("/chat")
//    public Result<String> chat(@RequestParam String msg) { // 旧版同步聊天接口：一次性返回完整回答，主要用于兼容非 SSE 调用。
//        log.info("接收到前端提问: {}", msg); // 打印请求参数//接收参数 / Log and receive the request parameter.
//        String response = agentService.chat(msg);
//        log.info("大模型生成完毕，准备返回: {}", response);
//        // 打印返回结果
//        // Log the response result.
//        return Result.success(response);
//    }

    @Operation(summary = "保存AI回复为笔记")
    @Log
    @PostMapping("/save-note")
    public Result<String> saveNote(@RequestBody ArticleSaveDTO dto) { // 将 AI 回复沉淀为笔记，后续会进入 MySQL 和向量同步链路。
        // 基础参数校验
        // Basic parameter validation.
        if (dto.getTitle() == null || dto.getTitle().trim().isEmpty()) {
            return Result.error(HttpServletResponse.SC_BAD_REQUEST, "笔记标题不能为空");
        }
        if (dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            return Result.error(HttpServletResponse.SC_BAD_REQUEST, "笔记内容不能为空");
        }
        // 执行入库
        // Persist the note.
        agentService.saveAiNote(dto);

        return Result.success("笔记已成功存入数据库");
    }

    @Operation(summary = "AI总结指定笔记")
    @PostMapping("/article/ai/summary/{id}")
    public Result<String> summarizeArticle(@PathVariable("id") Long articleId) { // 根据当前用户的文章生成 AI 摘要，权限校验在 Service 层完成。
        String summary = agentService.summarizeArticle(articleId);
        return Result.success(summary);
    }
}

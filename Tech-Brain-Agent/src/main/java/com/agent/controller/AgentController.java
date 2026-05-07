package com.agent.controller;

import com.agent.AgentService;
import com.agent.aopanno.Log;
import com.agent.entity.Result;
import com.agent.entity.dto.ArticleSaveDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AI问答与笔记保存接口")
@Slf4j
@RestController
public class AgentController {
    @Autowired
    private AgentService agentService;

    @Operation(summary = "智能RAG问答")
    @GetMapping("/chat")
    public Result<String> chat(@RequestParam String msg) {
        log.info("接收到前端提问: {}", msg); // 打印请求参数//接收参数 / Log and receive the request parameter.
        String response = agentService.chat(msg);
        log.info("大模型生成完毕，准备返回: {}", response);
        // 打印返回结果
        // Log the response result.
        return Result.success(response);
    }

    @Operation(summary = "保存AI回复为笔记")
    @Log
    @PostMapping("/save-note")
    public Result<String> saveNote(@RequestBody ArticleSaveDTO dto) {
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
}

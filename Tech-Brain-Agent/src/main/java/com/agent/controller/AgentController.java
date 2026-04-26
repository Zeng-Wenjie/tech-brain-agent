package com.agent.controller;

import com.agent.AgentService;
import com.agent.aopanno.Log;
import com.agent.entity.ArticleSaveDTO;
import com.agent.entity.Result;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
public class AgentController {
    @Autowired
            private AgentService agentService;
    @Autowired
    private EmbeddingModel embeddingModel;//注入向量模型
    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;//注入Redis向量库

    // 用 LangChain4j 的写法！
    GoogleAiGeminiChatModel model = GoogleAiGeminiChatModel.builder()
            .apiKey("AIzaSyAn67JDZGIEEUxEkNc5QpueZNQzOnlSa1g")//API Key
            .modelName("gemini-3-flash-preview")//模型名称
            .build();//创建模型

    @Log
    @GetMapping("/api/chat")
    public Result<String> chat(@RequestParam String msg) {
        log.info("接收到前端提问: {}", msg); // 打印请求参数//接收参数
        //

        // 发送消息并获取回复
        String response = model.generate(msg);
        log.info("大模型生成完毕，准备返回: {}", response);
        // 打印返回结果
        return Result.success(response);
    }

    @Log
    @PostMapping("/save-note")
    public Result<String> saveNote(@RequestBody ArticleSaveDTO dto) {
        // 基础参数校验
        if (dto.getTitle() == null || dto.getTitle().trim().isEmpty()) {
            return Result.error(400,"笔记标题不能为空");
        }
        if (dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            return Result.error(400,"笔记内容不能为空");
        }

        // 执行入库
        agentService.saveAiNote(dto);

        return Result.success("笔记已成功存入数据库");
    }

}

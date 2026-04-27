package com.agent.controller;

import com.agent.AgentService;
import com.agent.aopanno.Log;
import com.agent.entity.ArticleSaveDTO;
import com.agent.entity.Result;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "AI 问答与笔记管理")
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

    @Operation(summary = "智能 RAG 对话接口")
    @Log
    @GetMapping("/api/chat")
    public Result<String> chat(@RequestParam String msg) {
        log.info("接收到前端提问: {}", msg); // 打印请求参数//接收参数
        //RAG编写：
        // 将用户的提问转为向量
        Embedding embedding = embeddingModel.embed(msg).content();
        //构建查询参数
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(embedding)
                .maxResults(3)//最大返回条数为3
                .minScore(0.4)//最低相似度阔值为0.7
                .build();//构建查询参数

        //去Redis向量库进行相似度搜索
        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        List<EmbeddingMatch<TextSegment>> relatedEmbeddings = searchResult.matches();
        // 确认是不是查出来为 0
        log.info("=== 核心排查：从 Redis 中检索到的数据条数：{} ===", relatedEmbeddings.size());

        String context = relatedEmbeddings.stream()
                .map(match -> match.embedded().text())
                .collect(Collectors.joining("\n\n"));

        String prompt = "基于以下参考资料回答问题：\n" + context + "\n\n问题：" + msg;
        // 发送消息并获取回复
        String response = model.generate(prompt);
        log.info("大模型生成完毕，准备返回: {}", response);
        // 打印返回结果
        return Result.success(response);
    }

    @Operation(summary = "保存笔记接口")
    @Log
    @PostMapping("/save-note")
    public Result<String> saveNote(@RequestBody ArticleSaveDTO dto) {
        // 基础参数校验
        if (dto.getTitle() == null || dto.getTitle().trim().isEmpty()) {
            return Result.error(400, "笔记标题不能为空");
        }
        if (dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            return Result.error(400, "笔记内容不能为空");
        }

        // 执行入库
        agentService.saveAiNote(dto);

        return Result.success("笔记已成功存入数据库");
    }

}

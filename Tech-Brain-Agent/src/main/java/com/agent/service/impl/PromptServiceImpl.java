package com.agent.service.impl;

import com.agent.service.PromptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Service
public class PromptServiceImpl implements PromptService {

    private static final String CHAT_RAG_PROMPT = "classpath:prompts/chat-rag-prompt.txt"; // RAG 问答模板，负责约束模型基于检索上下文回答。
    private static final String PURE_CHAT_PROMPT = "classpath:prompts/pure-chat-prompt.txt"; // 无 Milvus 命中时的纯聊天模板。
    private static final String ARTICLE_SUMMARY_PROMPT = "classpath:prompts/article-summary-prompt.txt"; // 文章摘要专用模板，和聊天指令分离。

    @Autowired
    private ResourceLoader resourceLoader; // 从 resources/prompts 加载模板，便于不改 Java 代码也能调整 Prompt。

    @Override
    public String buildChatPrompt(String context, String question) {
        // 从资源文件读取RAG提示词。
        return loadPrompt(CHAT_RAG_PROMPT)
                .replace("{{context}}", safe(context)) // 占位符替换把 Milvus 检索结果注入 Prompt。
                .replace("{{question}}", safe(question)); // 当前问题单独占位，避免和上下文混在一起降低可读性。
    }

    @Override
    public String buildPureChatPrompt(String question) {
        // Milvus无命中时使用纯问答提示词。
        return loadPrompt(PURE_CHAT_PROMPT)
                .replace("{{question}}", safe(question)); // 纯聊天只需要用户问题，不注入知识库上下文。
    }

    @Override
    public String buildArticleSummaryPrompt(String title, String content) {
        // 从资源文件读取文章总结提示词。
        return loadPrompt(ARTICLE_SUMMARY_PROMPT)
                .replace("{{title}}", safe(title)) // 标题帮助模型把握文章主题。
                .replace("{{content}}", safe(content)); // 正文是生成摘要的主要输入。
    }

    private String loadPrompt(String location) {
        Resource resource = resourceLoader.getResource(location); // classpath 路径让 Prompt 随应用打包发布。
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Prompt文件加载失败: " + location, e); // Prompt 缺失属于启动/部署配置问题，直接抛出便于暴露。
        }
    }

    private String safe(String value) {
        return value == null ? "" : value; // 防止 null 字符串进入 Prompt 影响模型理解。
    }
}

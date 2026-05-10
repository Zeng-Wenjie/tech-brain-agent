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

    private static final String CHAT_RAG_PROMPT = "classpath:prompts/chat-rag-prompt.txt";
    private static final String ARTICLE_SUMMARY_PROMPT = "classpath:prompts/article-summary-prompt.txt";

    @Autowired
    private ResourceLoader resourceLoader;

    @Override
    public String buildChatPrompt(String context, String question) {
        // 从资源文件读取RAG提示词，便于后续维护。
        return loadPrompt(CHAT_RAG_PROMPT)
                .replace("{{context}}", safe(context))
                .replace("{{question}}", safe(question));
    }

    @Override
    public String buildArticleSummaryPrompt(String title, String content) {
        // 从资源文件读取总结提示词，避免Java代码硬编码大段文本。
        return loadPrompt(ARTICLE_SUMMARY_PROMPT)
                .replace("{{title}}", safe(title))
                .replace("{{content}}", safe(content));
    }

    private String loadPrompt(String location) {
        Resource resource = resourceLoader.getResource(location);
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Prompt文件加载失败: " + location, e);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}

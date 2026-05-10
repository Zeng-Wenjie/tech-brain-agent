package com.agent.service;

public interface PromptService {

    String buildChatPrompt(String context, String question);

    String buildArticleSummaryPrompt(String title, String content);
}

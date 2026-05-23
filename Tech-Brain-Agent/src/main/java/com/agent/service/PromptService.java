package com.agent.service;

public interface PromptService {

    String buildArticleSummaryPrompt(String title, String content);
}

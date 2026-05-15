package com.agent.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Slf4j
@Configuration
public class DeepSeekConfig {

    @Bean
    public ChatLanguageModel chatLanguageModel(@Value("${deepseek.api-key}") String apiKey,
                                               @Value("${deepseek.base-url}") String baseUrl,
                                               @Value("${deepseek.model-name}") String modelName,
                                               @Value("${deepseek.temperature}") Double temperature,
                                               @Value("${deepseek.timeout-seconds}") Long timeoutSeconds) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel(@Value("${deepseek.api-key}") String apiKey,
                                                                 @Value("${deepseek.base-url}") String baseUrl,
                                                                 @Value("${deepseek.model-name}") String modelName,
                                                                 @Value("${deepseek.temperature}") Double temperature,
                                                                 @Value("${deepseek.timeout-seconds}") Long timeoutSeconds) {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    @Bean
    public ApplicationRunner deepSeekModelLogger(@Value("${deepseek.model-name}") String modelName) {
        return args -> log.info("当前聊天模型供应商：DeepSeek，模型：{}", modelName);
    }
}

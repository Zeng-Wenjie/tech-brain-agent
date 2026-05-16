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
    public ChatLanguageModel chatLanguageModel(@Value("${deepseek.api-key}") String apiKey, // API Key 从配置读取，通常由环境变量注入，代码中不硬编码密钥。
                                               @Value("${deepseek.base-url}") String baseUrl, // baseUrl 指向 DeepSeek 兼容 OpenAI 协议的服务地址。
                                               @Value("${deepseek.model-name}") String modelName, // modelName 决定实际调用的聊天模型。
                                               @Value("${deepseek.temperature}") Double temperature, // temperature 控制回答发散程度，越低越稳定。
                                               @Value("${deepseek.timeout-seconds}") Long timeoutSeconds) { // timeout 防止同步生成长时间挂起请求。
        return OpenAiChatModel.builder()
                .apiKey(apiKey) // 不打印 apiKey，避免日志泄露敏感凭证。
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel(@Value("${deepseek.api-key}") String apiKey, // 流式模型同样使用配置注入的密钥。
                                                                 @Value("${deepseek.base-url}") String baseUrl,
                                                                 @Value("${deepseek.model-name}") String modelName,
                                                                 @Value("${deepseek.temperature}") Double temperature,
                                                                 @Value("${deepseek.timeout-seconds}") Long timeoutSeconds) { // SSE 场景需要流式模型逐 token 返回。
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    @Bean
    public ApplicationRunner deepSeekModelLogger(@Value("${deepseek.model-name}") String modelName) { // 只记录模型名称，不记录 API Key。
        return args -> log.info("当前聊天模型供应商：DeepSeek，模型：{}", modelName);
    }
}

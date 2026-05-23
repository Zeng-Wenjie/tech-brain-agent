package com.agent.toolcalling.client; // DeepSeek公共客户端回调包。

/**
 * DeepSeek流式响应回调接口。
 *
 * <p>该接口位于Tech-Brain-Tool公共模块，用于承接DeepSeek chat/completions stream=true 的增量输出。</p>
 * <p>调用链为：业务编排器调用DeepSeekClient.streamChatCompletions -> DeepSeekClient读取SSE流 -> 每个delta.content触发onToken -> 收到[DONE]或流结束触发onComplete。</p>
 * <p>本接口只定义流式回调契约，不依赖前端SSE、数据库、Milvus或具体Tool Calling业务。</p>
 */
public interface DeepSeekStreamCallback { // DeepSeek流式输出回调。

    void onToken(String token); // 收到choices[0].delta.content增量文本时触发。

    void onComplete(); // 收到data: [DONE]或流结束时触发。

    void onError(Throwable error); // 流式请求、状态码或解析异常时触发。
}

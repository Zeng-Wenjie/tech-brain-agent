package com.agent.toolcalling.core; // Tool Calling公共编排回调包。

/**
 * Tool Calling流式输出回调接口。
 *
 * <p>该接口位于Tech-Brain-Tool公共模块，是ToolCallingChatService对业务层暴露的流式回调契约。</p>
 * <p>调用链为：业务层调用ToolCallingChatService.chatStream(message, callback) -> 公共编排器执行Tool Calling -> DeepSeek流式返回token -> 通过本接口把token交给业务层。</p>
 * <p>本接口不暴露DeepSeekStreamCallback，避免上层业务直接依赖DeepSeek SSE细节。</p>
 */
public interface ToolCallingStreamCallback { // Tool Calling对上层业务暴露的流式回调。

    void onToken(String token); // 收到最终回答增量token时触发。

    void onComplete(); // 最终回答流式生成完成时触发。

    void onError(Throwable error); // Tool Calling编排、工具执行或流式模型调用异常时触发。

    default void onToolEvent(String eventName, String payloadJson) { // 工具执行过程中产生业务事件时触发，默认空实现避免破坏已有回调实现。
    }
}

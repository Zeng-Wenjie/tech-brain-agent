package com.agent.toolcalling.log;

/**
 * Tool Calling 调用日志记录回调。
 *
 * <p>适用场景：本接口定义在 Tech-Brain-Tool 公共模块中，作为极窄的日志回调契约，
 * 让 ToolCallingChatServiceImpl 可以记录工具执行日志，同时不直接依赖 Agent 模块或具体数据库服务。</p>
 * <p>调用链：ChatMessageServiceImpl 创建一个基于 ToolCallLogService 的实现并放入 ToolCallingRequestContext；
 * ToolCallingChatServiceImpl 在 force ragSearch、force summarizeArticle 和 model tool_call 三条路径中，
 * 围绕 AiTool.execute(arguments) 调用本回调创建和更新 tool_call_log。</p>
 * <p>边界说明：本接口不执行 SQL，不感知表结构，也不改变聊天 SSE 事件结构。
 * 调用方必须兜底捕获实现异常，不能让日志失败中断聊天主流程。</p>
 */
public interface ToolCallLogRecorder { // 跨模块使用的工具调用日志窄接口。

    Long createRunningLog(String traceId,
                          Long conversationId,
                          Long userId,
                          String userMessage,
                          String toolName,
                          String toolType,
                          String callSource,
                          String routeReason,
                          String argumentsJson); // 工具执行前创建一条运行中的工具调用日志。

    void markSuccess(Long id, String resultJson, Long durationMs); // 工具技术执行成功后更新 result_json 和 duration_ms。

    void markFailed(Long id, String errorMessage, Long durationMs); // 工具执行抛异常时更新 error_message 和 duration_ms。
}

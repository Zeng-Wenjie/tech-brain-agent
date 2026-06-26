package com.agent.toolcalling.devlog;

/**
 * 开发行为日志记录回调（P5.9 跨模块窄接口）。
 *
 * <p>适用场景：本接口定义在 Tech-Brain-Tool 公共模块中，作为极窄的开发日志保存回调契约，让
 * ToolCallingChatServiceImpl 在用户显式“保存上一条代码分析结果”时，能把保存动作委托给 Agent 模块，
 * 同时不让 Tech-Brain-Tool 反向依赖 Agent 的 DevActionLogService、Mapper 或数据库实现。</p>
 *
 * <p>调用链：ChatMessageServiceImpl 基于 DevActionLogService 创建本接口实现并放入 ToolCallingRequestContext；
 * ToolCallingChatServiceImpl 命中“保存到开发日志”意图且没有新分析目标时调用 saveLastCodeAnalysis，
 * 由实现读取当前会话最近一条成功的 analyzeCode tool_call_log，写入 dev_action_log，并返回保存结果。</p>
 *
 * <p>边界说明：本接口不执行 SQL，不感知表结构，也不改变聊天 SSE 事件结构；“分析并保存”链路不走本接口，
 * 而是由 AnalyzeCodeTool 在分析成功后直接调用 DevActionLogService 保存，保证 result_json 携带 devLogSaved。
 * 调用方必须兜底捕获实现异常，不能让开发日志保存失败中断聊天主流程。</p>
 */
public interface DevActionLogRecorder { // 跨模块使用的开发行为日志窄接口。

    DevActionLogSaveResult saveLastCodeAnalysis(Long userId,
                                                Long conversationId,
                                                String traceId); // 保存当前会话最近一条 analyzeCode 分析结果到开发日志。
}

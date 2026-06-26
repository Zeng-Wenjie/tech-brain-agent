package com.agent.entity.dto;

import lombok.Data;

/**
 * 开发行为日志保存入参（P5.9 代码分析结果落库）。
 *
 * <p>适用场景：作为 DevActionLogService.saveCodeAnalysisLog 的统一入参，承载一次 analyzeCode 代码分析结果
 * 保存到 dev_action_log 所需的全部上下文，避免 Service 方法参数过长，并让“分析并保存”和“保存上一条分析结果”
 * 两条链路复用同一套保存逻辑。</p>
 *
 * <p>调用链：AnalyzeCodeTool（saveToDevLog=true）或 DevActionLogService.saveLastCodeAnalysisLog 先构造本 DTO，
 * 再调用 DevActionLogService.saveCodeAnalysisLog 完成 title/summary 兜底、action_type 推导和落库。</p>
 *
 * <p>边界说明：本 DTO 不是前端请求体，不暴露接口，不执行 SQL；resultJson 只承载 analyzeCode 已脱敏的统一
 * 分析结果（workspace 相对路径），不携带文件内容或服务器绝对路径。</p>
 */
@Data // 使用 Lombok 生成 getter/setter，保持项目 DTO 风格。
public class DevActionLogSaveCommand { // 开发行为日志保存参数对象。

    private Long userId; // 当前用户 ID。
    private Long conversationId; // 当前会话 ID。
    private String traceId; // 与 tool_call_log 一致的链路追踪 ID。
    private Long toolCallLogId; // 来源 tool_call_log.id，可空。
    private String analysisType; // analyzeCode 的 analysisType，例如 RISK，用于推导 action_type。
    private String targetType; // 目标类型，例如 CLASS / TOOL / ENDPOINT，可空时由 Service 兜底推导。
    private String targetPath; // workspace 相对路径，绝不传服务器绝对路径。
    private String className; // 类名。
    private String methodName; // 方法名。
    private String endpoint; // 接口路径。
    private String toolName; // AI Tool 名称。
    private String eventName; // SSE 事件名。
    private String devLogTitle; // 可选，开发日志标题，为空时由 Service 按 analysisType 和目标自动生成。
    private String devLogSummary; // 可选，开发日志摘要，为空时由 Service 从结果自动提取。
    private String resultJson; // 必填，analyzeCode 统一分析结果 JSON。
}

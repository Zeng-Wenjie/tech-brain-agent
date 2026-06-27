package com.agent.entity.dto;

import lombok.Data;

/**
 * 开发行为日志统一保存入参（P6.1 语义化开发日志）。
 *
 * <p>适用场景：作为 DevActionLogService.saveDevAction 的统一内部入参，承载一条开发行为日志的全部语义字段，
 * 让“代码分析（P5.9）”以及后续 P7-P14（修改方案/patch/文件修改/编译/回滚）各类行为都通过同一个入口落库，
 * 为 P17 记忆召回、P18 记忆质量筛选提前准备 intent / result / targetModule / targetFile / relatedBugId 等字段。</p>
 *
 * <p>调用链：SelfDevOrchestrator 或后续沙箱/验证/回滚流程先构造本对象（多数字段可空，由 Service 兜底/推断），
 * 再调用 saveDevAction 完成 intent/summary/targetModule 自动补全、路径脱敏、长度控制后写入 dev_action_log。</p>
 *
 * <p>边界说明：本对象是内部 Service DTO，不是前端接口请求体，不暴露 Controller；
 * resultJson 只承载已脱敏的统一分析结果，不携带源码全文或服务器绝对路径。</p>
 */
@Data // 使用 Lombok 生成 getter/setter，保持项目 DTO 风格。
public class DevActionLogCreateRequest { // 开发行为日志统一保存入参。

    private Long userId;          // 当前用户 ID。
    private Long conversationId;  // 当前会话 ID。
    private String traceId;       // 与 tool_call_log 一致的链路追踪 ID，用于串起一次完整开发流程。
    private Long toolCallLogId;   // 来源 tool_call_log.id，可空；缺失时 Service 可按 traceId 回查。
    private String actionType;    // 开发行为类型，对应 DevActionType；为空时默认 CODE_ANALYSIS。
    private String intent;        // 行为意图（为什么做）；为空时 Service 按 analysisType/target 自动生成。
    private String result;        // 行为结果质量，对应 DevActionResult；为空时默认 SUCCESS。
    private String analysisType;  // 历史分析类型，可空；Claude Code 新链路通常不使用。
    private String targetType;    // 目标类型，对应 DevTargetType；为空时由 Service 推断。
    private String targetModule;  // 目标模块（如 Tech-Brain-Agent）；为空时由 Service 从 targetFile/targetPath 推断。
    private String targetFile;    // 目标文件 workspace 相对路径（P17 召回优先用）；与 targetPath 互相兜底。
    private String targetPath;    // 历史兼容文件路径（P5.9 已用）；与 targetFile 互相兜底。
    private String className;     // 类名。
    private String methodName;    // 方法名。
    private String endpoint;      // 接口路径。
    private String toolName;      // AI Tool 名称。
    private String eventName;     // SSE 事件名。
    private String relatedBugId;  // 关联 bug / 问题编号，可空；为空时 Service 尝试从文本提取。
    private String title;         // 开发日志标题；为空时 Service 自动生成。
    private String summary;       // 自然语言语义摘要（P17 向量化核心）；为空时 Service 自动生成。
    private String resultJson;    // 统一分析结果 JSON；Service 控制最大长度并脱敏。
    private String status;        // 日志保存状态，对应 DevActionStatus；为空时默认 SUCCESS。
    private String errorMsg;      // 失败原因，可空。
}

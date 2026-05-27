package com.agent.entity.dto;

import lombok.Data;

/**
 * Tool Calling 调用日志创建入参。
 *
 * <p>适用场景：作为 ToolCallLogService.createRunningLog 的内部服务入参，承载一次工具调用开始时
 * 已知的上下文信息，避免 Service 方法参数过长。</p>
 * <p>调用链：后续 Tool Calling 编排链路会在实际调用工具前构造本 DTO，
 * 传入 ToolCallLogService 创建 success=0 的运行中日志，工具返回后再用日志 ID 更新成功或失败状态。</p>
 * <p>边界说明：本 DTO 不是前端请求体，不暴露接口，不执行 SQL，不接入 /chat/message 主流程。</p>
 */
@Data // 使用 Lombok 生成 getter/setter，保持项目 DTO 风格。
public class ToolCallLogCreateRequest { // Tool 调用日志创建参数对象。

    private String traceId; // 同一轮 Tool Calling 调用链路的追踪 ID。
    private Long conversationId; // 当前会话 ID。
    private Long userId; // 当前用户 ID。
    private String userMessage; // 当前用户原始消息。
    private String toolName; // 工具名称，为空时 Service 会兜底为 UNKNOWN。
    private String toolType; // 工具类型。
    private String callSource; // 调用来源。
    private String routeReason; // 工具路由原因。
    private String argumentsJson; // 工具调用参数 JSON。
}

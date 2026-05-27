package com.agent.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具调用日志展示对象。
 *
 * <p>适用场景：后台分页查询和详情查询 tool_call_log 时使用，分页列表优先返回预览字段，
 * 详情接口返回完整 argumentsJson、resultJson、finalAnswer 和 errorMessage。</p>
 * <p>调用链：ToolCallLogServiceImpl 将 ToolCallLog 实体转换为本对象，
 * ToolCallLogController 再通过 Result 返回给后台查询接口。</p>
 * <p>边界说明：本对象只负责出参展示，不执行 SQL，不修改数据库结构，也不改变聊天 SSE 或工具调用主链路。</p>
 */
@Data // 使用 Lombok 生成 getter 和 setter。
@Schema(description = "工具调用日志展示对象")
public class ToolCallLogVO { // 工具调用日志查询返回对象。

    @Schema(description = "日志 ID")
    private Long id; // tool_call_log 主键 ID。

    @Schema(description = "本轮聊天请求追踪 ID")
    private String traceId; // 同一轮工具调用共享的追踪 ID。

    @Schema(description = "会话 ID")
    private Long conversationId; // 工具调用所属会话 ID。

    @Schema(description = "用户 ID")
    private Long userId; // 工具调用所属用户 ID。

    @Schema(description = "用户原始输入")
    private String userMessage; // 详情接口返回完整用户输入。

    @Schema(description = "工具名称")
    private String toolName; // 工具名称，例如 ragSearch。

    @Schema(description = "工具类型")
    private String toolType; // 工具类型，例如 RAG。

    @Schema(description = "调用来源")
    private String callSource; // 调用来源，例如 FORCE_ROUTE。

    @Schema(description = "路由原因")
    private String routeReason; // 触发工具调用的路由原因。

    @Schema(description = "工具入参 JSON")
    private String argumentsJson; // 详情接口返回完整工具入参 JSON。

    @Schema(description = "工具结果 JSON")
    private String resultJson; // 详情接口返回完整工具结果 JSON。

    @Schema(description = "最终聊天气泡回答")
    private String finalAnswer; // 详情接口返回完整最终回答。

    @Schema(description = "执行状态，1 表示成功，0 表示失败或运行中")
    private Integer success; // 工具调用技术执行状态。

    @Schema(description = "错误信息")
    private String errorMessage; // 详情接口返回完整错误信息。

    @Schema(description = "工具执行耗时，单位毫秒")
    private Long durationMs; // 工具执行耗时。

    @Schema(description = "创建时间")
    private LocalDateTime createTime; // 日志创建时间。

    @Schema(description = "更新时间")
    private LocalDateTime updateTime; // 日志更新时间。

    @Schema(description = "用户原始输入预览")
    private String userMessagePreview; // 分页列表展示用户输入预览。

    @Schema(description = "工具入参预览")
    private String argumentsPreview; // 分页列表展示工具入参预览。

    @Schema(description = "工具结果预览")
    private String resultPreview; // 分页列表展示工具结果预览。

    @Schema(description = "最终回答预览")
    private String finalAnswerPreview; // 分页列表展示最终回答预览。

    @Schema(description = "错误信息预览")
    private String errorMessagePreview; // 分页列表展示错误信息预览。
}

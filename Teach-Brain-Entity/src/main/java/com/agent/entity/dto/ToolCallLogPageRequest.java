package com.agent.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 工具调用日志分页查询参数。
 *
 * <p>适用场景：后台查询 tool_call_log 时承载分页参数和筛选条件，用于定位 ragSearch、
 * summarizeArticle 或模型主动 tool_call 的执行问题。</p>
 * <p>调用链：ToolCallLogController 接收本对象并强制写入当前登录用户 ID，
 * 再传给 ToolCallLogService.pageToolCallLogs 构造 MyBatis-Plus 分页查询。</p>
 * <p>边界说明：本对象只是查询入参，不执行 SQL，不修改数据库结构，也不参与 Tool Calling 主链路执行。</p>
 */
@Data // 使用 Lombok 生成 getter 和 setter。
@Schema(description = "工具调用日志分页查询参数")
public class ToolCallLogPageRequest { // 后台工具调用日志分页筛选入参。

    @Schema(description = "当前页码，默认 1", example = "1")
    private Integer pageNum = 1; // 当前页码，Service 层会兜底修正非法值。

    @Schema(description = "每页数量，默认 10，最大 100", example = "10")
    private Integer pageSize = 10; // 每页数量，Service 层会限制最大值。

    @Schema(description = "本轮聊天请求追踪 ID", example = "8f5f4d36b1c84f5a9e6f1e9b8f0c2d7a")
    private String traceId; // 按 trace_id 精确查询同一轮工具调用日志。

    @Schema(description = "会话 ID", example = "1")
    private Long conversationId; // 按 conversation_id 查询指定会话下的工具调用日志。

    @Schema(description = "用户 ID，Controller 会强制覆盖为当前登录用户", example = "1")
    private Long userId; // 用户隔离字段，前端传入值不会被信任。

    @Schema(description = "工具名称，例如 ragSearch 或 summarizeArticle", example = "ragSearch")
    private String toolName; // 按 tool_name 精确筛选。

    @Schema(description = "工具类型，例如 RAG、SUMMARY、UNKNOWN", example = "RAG")
    private String toolType; // 按 tool_type 精确筛选。

    @Schema(description = "调用来源，例如 FORCE_ROUTE 或 MODEL_TOOL_CALL", example = "FORCE_ROUTE")
    private String callSource; // 按 call_source 精确筛选。

    @Schema(description = "执行状态，1 表示成功，0 表示失败或运行中", example = "1")
    private Integer success; // 按 success 精确筛选。

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) // 支持查询参数传入标准日期时间。
    @Schema(description = "开始时间，格式示例：2026-05-27T00:00:00", example = "2026-05-27T00:00:00")
    private LocalDateTime startTime; // create_time 查询起始时间。

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) // 支持查询参数传入标准日期时间。
    @Schema(description = "结束时间，格式示例：2026-05-27T23:59:59", example = "2026-05-27T23:59:59")
    private LocalDateTime endTime; // create_time 查询结束时间。
}

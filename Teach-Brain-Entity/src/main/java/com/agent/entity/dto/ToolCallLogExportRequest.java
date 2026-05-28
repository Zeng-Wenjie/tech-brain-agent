package com.agent.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 工具调用日志 CSV 导出查询参数。
 *
 * <p>适用场景：后台按当前筛选条件导出 tool_call_log 日志时使用，
 * 筛选字段与分页查询保持一致，但不包含 pageNum 和 pageSize。</p>
 * <p>调用链：ToolCallLogController 接收本对象并调用 ToolCallLogService.listForExport，
 * Service 从 UserContext 读取当前登录用户 ID 后查询当前用户可见日志。</p>
 * <p>边界说明：本对象不接收 userId，不允许前端指定导出其它用户日志，不执行 SQL，
 * 不修改数据库结构，也不影响日志写入、分页、详情、统计或聊天主链路。</p>
 */
@Data // 使用 Lombok 生成 getter 和 setter。
@Schema(description = "工具调用日志 CSV 导出查询参数")
public class ToolCallLogExportRequest { // 后台工具调用日志导出筛选入参。

    @Schema(description = "本轮聊天请求追踪 ID", example = "8f5f4d36b1c84f5a9e6f1e9b8f0c2d7a")
    private String traceId; // 按 trace_id 精确筛选，空字符串和 Swagger 默认 string 会被忽略。

    @Schema(description = "会话 ID", example = "1")
    private Long conversationId; // 按 conversation_id 精确筛选。

    @Schema(description = "工具名称，例如 ragSearch 或 summarizeArticle", example = "ragSearch")
    private String toolName; // 按 tool_name 精确筛选，空字符串和 Swagger 默认 string 会被忽略。

    @Schema(description = "工具类型，例如 RAG、SUMMARY、UNKNOWN", example = "RAG")
    private String toolType; // 按 tool_type 精确筛选，空字符串和 Swagger 默认 string 会被忽略。

    @Schema(description = "调用来源，例如 FORCE_ROUTE 或 MODEL_TOOL_CALL", example = "FORCE_ROUTE")
    private String callSource; // 按 call_source 精确筛选，空字符串和 Swagger 默认 string 会被忽略。

    @Schema(description = "执行状态，1 表示成功，0 表示失败或运行中", example = "1")
    private Integer success; // 按 success 精确筛选。

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) // 支持 GET 查询参数绑定标准日期时间。
    @Schema(description = "开始时间，格式示例：2026-05-27T00:00:00", example = "2026-05-27T00:00:00")
    private LocalDateTime startTime; // create_time 导出起始时间。

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) // 支持 GET 查询参数绑定标准日期时间。
    @Schema(description = "结束时间，格式示例：2026-05-27T23:59:59", example = "2026-05-27T23:59:59")
    private LocalDateTime endTime; // create_time 导出结束时间。

    @Schema(description = "是否导出完整参数、结果和最终回答，默认 false", example = "false")
    private Boolean includeDetail; // true 时导出受限长度的完整字段，false 时导出预览字段。
}

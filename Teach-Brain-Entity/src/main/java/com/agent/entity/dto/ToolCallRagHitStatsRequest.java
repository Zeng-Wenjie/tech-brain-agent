package com.agent.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * RAG 工具命中率统计查询参数。
 *
 * <p>适用场景：后台统计 ragSearch 工具在 tool_call_log 中的命中情况时使用，
 * 支持按会话、调用来源和创建时间范围缩小统计范围。</p>
 * <p>调用链：ToolCallLogController 接收本对象，ToolCallLogServiceImpl 从 UserContext
 * 获取当前登录用户 ID，并固定 tool_name=ragSearch 查询日志后解析 result_json。</p>
 * <p>边界说明：本对象不接收 userId，不允许前端指定统计其它用户数据，不执行 SQL，
 * 不修改数据库结构，也不影响 RAG 工具调用和聊天主链路。</p>
 */
@Data // 使用 Lombok 生成 getter 和 setter。
@Schema(description = "RAG 工具命中率统计查询参数")
public class ToolCallRagHitStatsRequest { // 后台 RAG 命中率统计筛选入参。

    @Schema(description = "会话 ID", example = "1")
    private Long conversationId; // 按 conversation_id 精确筛选指定会话内的 RAG 调用统计。

    @Schema(description = "调用来源，例如 FORCE_ROUTE 或 MODEL_TOOL_CALL", example = "FORCE_ROUTE")
    private String callSource; // 按 call_source 精确筛选，空字符串和 Swagger 默认 string 会被忽略。

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) // 支持 GET 查询参数绑定标准日期时间。
    @Schema(description = "开始时间，格式示例：2026-05-27T00:00:00", example = "2026-05-27T00:00:00")
    private LocalDateTime startTime; // create_time 统计起始时间。

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) // 支持 GET 查询参数绑定标准日期时间。
    @Schema(description = "结束时间，格式示例：2026-05-27T23:59:59", example = "2026-05-27T23:59:59")
    private LocalDateTime endTime; // create_time 统计结束时间。
}

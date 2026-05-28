package com.agent.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 工具调用统计查询参数。
 *
 * <p>适用场景：后台按工具名称统计 tool_call_log 数据时承载筛选条件，
 * 用于定位 ragSearch、summarizeArticle 或模型主动工具调用的调用量、失败率和耗时表现。</p>
 * <p>调用链：ToolCallLogController 接收本对象，ToolCallLogServiceImpl 从 UserContext
 * 读取当前登录用户 ID 后构造 MyBatis-Plus 分组统计查询。</p>
 * <p>边界说明：本对象不接收 userId，不允许前端指定统计其它用户数据，不执行 SQL，
 * 不修改数据库结构，也不影响 Tool Calling 主链路。</p>
 */
@Data // 使用 Lombok 生成 getter 和 setter。
@Schema(description = "工具调用统计查询参数")
public class ToolCallStatsRequest { // 后台工具调用统计筛选入参。

    @Schema(description = "工具名称，例如 ragSearch 或 summarizeArticle", example = "ragSearch")
    private String toolName; // 按 tool_name 精确筛选，空字符串和 Swagger 默认 string 会被忽略。

    @Schema(description = "会话 ID", example = "1")
    private Long conversationId; // 按 conversation_id 精确筛选指定会话内的工具调用统计。

    @Schema(description = "调用来源，例如 FORCE_ROUTE 或 MODEL_TOOL_CALL", example = "FORCE_ROUTE")
    private String callSource; // 按 call_source 精确筛选，空字符串和 Swagger 默认 string 会被忽略。

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) // 支持 GET 查询参数绑定标准日期时间。
    @Schema(description = "开始时间，格式示例：2026-05-27T00:00:00", example = "2026-05-27T00:00:00")
    private LocalDateTime startTime; // create_time 统计起始时间。

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) // 支持 GET 查询参数绑定标准日期时间。
    @Schema(description = "结束时间，格式示例：2026-05-27T23:59:59", example = "2026-05-27T23:59:59")
    private LocalDateTime endTime; // create_time 统计结束时间。
}

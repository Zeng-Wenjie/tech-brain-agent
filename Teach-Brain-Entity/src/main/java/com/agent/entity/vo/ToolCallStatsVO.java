package com.agent.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 工具调用统计返回对象。
 *
 * <p>适用场景：后台按工具名称查看 tool_call_log 汇总结果时使用，
 * 展示每个工具的调用量、成功次数、失败次数、失败率和平均耗时。</p>
 * <p>调用链：ToolCallLogServiceImpl 执行分组统计后转换为本对象，
 * ToolCallLogController 再通过统一 Result 返回给后台统计接口。</p>
 * <p>边界说明：本对象只负责出参展示，不执行 SQL，不修改数据库结构，
 * 不参与工具调用写入逻辑，也不影响分页和详情接口。</p>
 */
@Data // 使用 Lombok 生成 getter 和 setter。
@Schema(description = "工具调用统计返回对象")
public class ToolCallStatsVO { // 后台工具调用统计返回对象。

    @Schema(description = "工具名称")
    private String toolName; // 分组统计的工具名称。

    @Schema(description = "工具类型")
    private String toolType; // 分组统计的工具类型。

    @Schema(description = "调用总次数")
    private Long totalCount; // 当前工具在筛选范围内的调用量。

    @Schema(description = "成功次数")
    private Long successCount; // success=1 的调用次数。

    @Schema(description = "失败次数")
    private Long failureCount; // success=0 的调用次数。

    @Schema(description = "失败率，0.1250 表示 12.5%")
    private BigDecimal failureRate; // 失败次数除以调用总次数，保留四位小数。

    @Schema(description = "平均耗时，单位毫秒")
    private BigDecimal avgDurationMs; // duration_ms 的平均值，保留两位小数。
}

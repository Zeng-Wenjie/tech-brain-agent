package com.agent.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * RAG 工具命中率统计返回对象。
 *
 * <p>适用场景：后台查看 ragSearch 工具在当前用户 tool_call_log 中的命中质量时使用，
 * 展示总调用数、命中数、未命中数、无法解析数、命中率、平均命中数量和最大命中数量。</p>
 * <p>调用链：ToolCallLogServiceImpl 查询 ragSearch 日志并解析 result_json 后转换为本对象，
 * ToolCallLogController 再通过统一 Result 返回给后台统计接口。</p>
 * <p>边界说明：本对象只负责出参展示，不执行 SQL，不修改数据库结构，
 * 不参与工具日志写入，也不影响日志分页、详情和工具调用统计接口。</p>
 */
@Data // 使用 Lombok 生成 getter 和 setter。
@Schema(description = "RAG 工具命中率统计返回对象")
public class ToolCallRagHitStatsVO { // 后台 RAG 命中率统计返回对象。

    @Schema(description = "RAG 总调用次数")
    private Long totalCount; // ragSearch 在筛选范围内的总调用次数。

    @Schema(description = "RAG 命中次数")
    private Long hitCount; // 命中数量大于 0 的调用次数。

    @Schema(description = "RAG 未命中次数")
    private Long emptyCount; // 命中数量等于 0 的调用次数。

    @Schema(description = "无法解析命中数量的次数")
    private Long unknownCount; // 历史 result_json 无法解析命中数量的调用次数。

    @Schema(description = "RAG 命中率，0.1250 表示 12.5%")
    private BigDecimal hitRate; // 命中次数除以可解析调用次数，保留四位小数。

    @Schema(description = "命中记录的平均命中数量")
    private BigDecimal avgHitSize; // 只统计命中记录的平均命中数量，保留两位小数。

    @Schema(description = "单次最大命中数量")
    private Integer maxHitSize; // 命中记录中的最大命中数量，没有命中时为 0。
}

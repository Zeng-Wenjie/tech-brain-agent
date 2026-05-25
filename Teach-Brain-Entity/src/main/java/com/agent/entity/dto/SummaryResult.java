package com.agent.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 通用内容总结结果 DTO。
 *
 * <p>适用场景：承载 SummaryService 对任意来源内容生成后的结构化总结结果，后续可同时服务弹窗展示、聊天气泡提示和保存类业务。</p>
 * <p>当前调用链：SummaryServiceImpl 调用 DeepSeek 同步模型生成 summary -> 封装 SummaryResult -> Controller/Tool/业务 Service 按 displayMode 使用。</p>
 * <p>边界说明：本类只描述返回数据，不保存数据库、不直接返回 SSE、不参与 Tool Calling 路由。</p>
 */
@Data // 使用 Lombok 生成 getter/setter，保持和项目现有 DTO 风格一致。
@Schema(description = "通用内容总结结果") // Swagger 文档描述，便于后续接口展示结构化返回。
public class SummaryResult { // 任意内容总结的统一返回模型。

    private String sourceType; // 内容来源类型，回填 SummaryRequest 标准化后的 sourceType。

    private Long sourceId; // 来源业务 ID，回填 SummaryRequest.sourceId。

    private String title; // 内容标题，回填标准化后的标题。

    private String summaryType; // 总结类型，回填标准化后的 summaryType。

    private String displayMode; // 展示方式，回填标准化后的 displayMode。

    private String summary; // 完整总结内容，供弹窗、保存结果或其它业务使用。

    private String chatMessage; // 聊天窗口短提示，Tool Calling 总结类工具后续应返回该字段而不是完整 summary。
}

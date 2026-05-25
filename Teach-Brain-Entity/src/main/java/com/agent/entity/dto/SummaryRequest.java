package com.agent.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 通用内容总结请求 DTO。
 *
 * <p>适用场景：承载文章、笔记、纯文本、爬虫文章、文件、URL 等任意内容的总结输入，供 SummaryService 统一构造 Prompt 并调用大模型。</p>
 * <p>当前调用链：Controller/Tool/业务 Service -> SummaryService.summarize(request) -> SummaryServiceImpl -> DeepSeek 同步模型 -> SummaryResult。</p>
 * <p>边界说明：本类只描述请求参数，不访问数据库、不触发 Tool Calling、不修改 /chat/message 主链路。</p>
 */
@Data // 使用 Lombok 生成 getter/setter，保持和项目现有 DTO 风格一致。
@Schema(description = "通用内容总结请求参数") // Swagger 文档描述，方便后续接口复用。
public class SummaryRequest { // 任意内容总结的统一入参模型。

    private String sourceType; // 内容来源类型，支持 ARTICLE、NOTE、TEXT、CRAWLER_ARTICLE、FILE、URL。

    private Long sourceId; // 来源业务 ID，例如文章 ID、笔记 ID、文件 ID，纯文本总结可以为空。

    private String title; // 内容标题，缺省时由 SummaryServiceImpl 兜底为“未命名内容”。

    private String content; // 待总结正文，SummaryServiceImpl 会校验非空并限制进入模型的长度。

    private String summaryType; // 总结类型，支持 normal、points、interview，缺省时使用 normal。

    private String displayMode; // 展示方式，支持 dialog、chat、save，缺省时使用 dialog。
}

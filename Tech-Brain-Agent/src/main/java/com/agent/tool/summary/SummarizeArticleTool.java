package com.agent.tool.summary;

import com.agent.entity.Article;
import com.agent.entity.dto.SummaryRequest;
import com.agent.entity.dto.SummaryResult;
import com.agent.mapper.AgentMapper;
import com.agent.service.SummaryService;
import com.agent.toolcalling.support.AbstractAiTool;
import com.agent.utils.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * summarizeArticle 文章/笔记总结工具。
 *
 * <p>适用场景：当用户在聊天中要求总结某一篇指定 ID 的文章、笔记、代码笔记，或要求整理指定文章的要点、面试话术时，由 Tool Calling 调用本工具。</p>
 * <p>当前调用链：ToolCallingChatServiceImpl 识别模型 tool_call -> ToolRegistry 获取 summarizeArticle -> SummarizeArticleTool.execute(arguments)
 * -> 按 articleId + UserContext.userId 查询 Article -> SummaryService.summarize(request) -> 返回包含 summary 和 chatMessage 的结构化 JSON。</p>
 * <p>边界说明：本工具位于 Tech-Brain-Agent 业务模块，不放入 Tech-Brain-Tool 公共模块；本工具不修改数据库、不执行 SQL、不接管 /chat/message 路径、不改变原 AI 总结按钮。</p>
 */
@Slf4j // 输出 [SummarizeArticleTool] 前缀日志，便于验证工具执行链路。
@Component // 注册为 Spring Bean，让 ToolRegistry 自动发现 summarizeArticle 工具。
public class SummarizeArticleTool extends AbstractAiTool { // 继承公共工具基类，复用参数 Schema 和 JSON 辅助方法。

    private static final String TOOL_NAME = "summarizeArticle"; // 工具名称必须和 DeepSeek tool_call.function.name 一致。

    private static final String TOOL_DESCRIPTION = "Summarize one of the user's notes or articles by articleId. Use this tool when the user asks to summarize a specific note, article, document, code note, or asks to extract key points or interview talking points from a specific article. The articleId is required."; // 给模型看的工具说明。

    private static final String RESULT_TYPE = "article_summary"; // 工具结果类型，供 ToolCallingChatServiceImpl 判断只输出 chatMessage。

    private static final String DEFAULT_SUMMARY_TYPE = "normal"; // summaryType 缺省值。

    private static final int PREVIEW_MAX_LENGTH = 80; // 日志 preview 最大长度，避免输出完整 summary。

    private final AgentMapper articleMapper; // 复用 Agent 模块当前文章 Mapper，按 articleId + userId 查询 Article。

    private final SummaryService summaryService; // 复用第 5.1 抽取的通用总结服务。

    public SummarizeArticleTool(AgentMapper articleMapper, SummaryService summaryService) { // 构造器注入业务依赖，便于测试和避免字段注入。
        this.articleMapper = articleMapper; // 保存 Article 查询入口。
        this.summaryService = summaryService; // 保存通用总结服务。
    }

    @Override // 实现 AiTool 工具名。
    public String name() {
        return TOOL_NAME; // 固定返回 summarizeArticle。
    }

    @Override // 实现 AiTool 工具描述。
    public String description() {
        return TOOL_DESCRIPTION; // 返回给模型判断调用时机的描述。
    }

    @Override // 实现 AiTool 参数 Schema。
    public ObjectNode parametersSchema() {
        ObjectNode schema = createObjectSchema(); // 创建顶层 object schema。
        addProperty(schema, "articleId", createIntegerProperty("Article or note ID to summarize."), true); // articleId 必填。
        ObjectNode summaryTypeProperty = createStringProperty("Summary type: normal, points, or interview. Default is normal."); // summaryType 可选。
        ArrayNode enumValues = objectMapper.createArrayNode(); // 构造可选枚举值，帮助模型稳定输出。
        enumValues.add("normal"); // 普通摘要。
        enumValues.add("points"); // 要点总结。
        enumValues.add("interview"); // 面试话术。
        summaryTypeProperty.set("enum", enumValues); // 写入 JSON Schema enum。
        addProperty(schema, "summaryType", summaryTypeProperty, false); // summaryType 非必填。
        return schema; // 返回完整参数 Schema。
    }

    @Override // 执行 summarizeArticle 工具。
    public String execute(JsonNode arguments) {
        log.info("[SummarizeArticleTool] execute summarizeArticle"); // 标记工具开始执行。
        Long articleId = readArticleId(arguments); // 从模型 arguments 中读取 articleId。
        String summaryType = normalizeSummaryType(getOptionalText(arguments, "summaryType", DEFAULT_SUMMARY_TYPE)); // 读取并标准化 summaryType。
        log.info("[SummarizeArticleTool] articleId: {}, summaryType: {}", articleId, summaryType); // 打印工具入参，不打印正文。

        Long currentUserId = UserContext.getUserId(); // 用户身份只从后端 ThreadLocal 获取，不信任模型或前端参数。
        log.info("[SummarizeArticleTool] current userId: {}", currentUserId); // 打印当前用户 ID，便于验证权限隔离。
        if (articleId == null || articleId <= 0) { // articleId 缺失或非法时不查库。
            return buildFailureResult(null, null, "缺少文章 ID，无法总结。"); // 返回结构化失败 JSON。
        }

        Article article = articleMapper.selectOne(new LambdaQueryWrapper<Article>() // 按文章 ID 和当前用户 ID 查询。
                .eq(Article::getId, articleId) // 限定文章 ID。
                .eq(Article::getUserId, currentUserId)); // 限定当前用户，防止越权总结他人文章。
        log.info("[SummarizeArticleTool] article found: {}", article != null); // 只打印是否找到，不打印文章内容。
        if (article == null) { // 未找到或无权限时返回统一错误。
            return buildFailureResult(articleId, null, "未找到该文章，或当前用户无权访问。"); // 不泄露其它用户文章是否存在。
        }

        String title = normalizeTitle(article.getTitle()); // 标准化标题，避免错误提示里出现空标题。
        String content = article.getContent(); // 读取文章正文。
        int contentLength = content == null ? 0 : content.trim().length(); // 统计正文长度，不打印正文。
        log.info("[SummarizeArticleTool] article content length: {}", contentLength); // 打印正文长度。
        if (content == null || content.trim().isEmpty()) { // 空正文无法总结。
            return buildFailureResult(articleId, title, "《" + title + "》这篇笔记内容为空，无法总结。"); // 返回结构化失败 JSON。
        }

        SummaryRequest request = new SummaryRequest(); // 构造通用总结请求。
        request.setSourceType("ARTICLE"); // 当前实体和现有接口均命名为 Article，sourceType 使用 ARTICLE。
        request.setSourceId(articleId); // 传入来源文章 ID。
        request.setTitle(title); // 传入文章标题。
        request.setContent(content); // 传入文章正文，SummaryService 内部做长度限制。
        request.setSummaryType(summaryType); // 传入模型识别出的总结类型。
        request.setDisplayMode("dialog"); // 后续完整 summary 通过弹窗展示，本步骤先放在工具 JSON 中。

        log.info("[SummarizeArticleTool] call SummaryService"); // 标记开始调用通用总结服务。
        SummaryResult summaryResult = summaryService.summarize(request); // 复用通用总结服务生成完整 summary 和 chatMessage。
        log.info("[SummarizeArticleTool] summary generated"); // 标记总结生成完成。
        log.info("[SummarizeArticleTool] summary preview: {}", previewContent(summaryResult.getSummary())); // 只打印 summary 短预览。
        return buildSuccessResult(articleId, title, summaryType, summaryResult); // 返回结构化 JSON 字符串。
    }

    private Long readArticleId(JsonNode arguments) {
        if (arguments == null || arguments.isMissingNode() || arguments.isNull()) { // arguments 为空时无法读取 articleId。
            return null; // 返回 null 交给 execute 生成结构化错误。
        }
        JsonNode articleIdNode = arguments.path("articleId"); // 读取 articleId 字段。
        if (articleIdNode.isMissingNode() || articleIdNode.isNull()) { // 字段不存在或 null。
            return null; // 返回 null 表示缺少文章 ID。
        }
        if (articleIdNode.isNumber()) { // 模型按 integer 输出时走数字解析。
            return articleIdNode.asLong(); // 返回 long 类型 ID。
        }
        String articleIdText = articleIdNode.asText(); // 兼容模型把 articleId 输出为字符串。
        if (articleIdText == null || articleIdText.trim().isEmpty()) { // 空字符串视为缺失。
            return null; // 返回 null 表示缺少文章 ID。
        }
        try { // 尝试解析字符串 ID。
            return Long.parseLong(articleIdText.trim()); // 返回解析后的文章 ID。
        } catch (NumberFormatException e) { // 非数字字符串属于非法参数。
            return null; // 返回 null 让 execute 输出统一缺少 ID 文案。
        }
    }

    private String normalizeSummaryType(String summaryType) {
        if (summaryType == null || summaryType.trim().isEmpty()) { // 未提供时使用普通摘要。
            return DEFAULT_SUMMARY_TYPE; // 返回 normal。
        }
        String normalizedSummaryType = summaryType.trim().toLowerCase(); // 统一小写。
        return switch (normalizedSummaryType) { // 只允许三种总结类型。
            case "normal", "points", "interview" -> normalizedSummaryType; // 合法值直接返回。
            default -> DEFAULT_SUMMARY_TYPE; // 非法值兜底 normal。
        };
    }

    private String normalizeTitle(String title) {
        if (title == null || title.trim().isEmpty()) { // 空标题兜底。
            return "未命名内容"; // 与 SummaryService 标题兜底保持一致。
        }
        return title.trim(); // 去掉首尾空白。
    }

    private String buildFailureResult(Long articleId, String title, String chatMessage) {
        ObjectNode result = objectMapper.createObjectNode(); // 使用 ObjectNode 构造 JSON，避免手拼转义错误。
        result.put("type", RESULT_TYPE); // 写入工具结果类型。
        result.put("success", false); // 标记执行失败。
        if (articleId != null) { // 有 articleId 时写入，缺少 ID 场景不写。
            result.put("articleId", articleId); // 写入文章 ID。
        }
        if (title != null && !title.isBlank()) { // 有标题时写入。
            result.put("title", title); // 写入文章标题。
        }
        result.put("chatMessage", chatMessage); // 写入聊天气泡短提示。
        result.put("summary", ""); // 失败场景 summary 固定为空。
        return result.toString(); // 返回结构化 JSON 字符串。
    }

    private String buildSuccessResult(Long articleId, String title, String summaryType, SummaryResult summaryResult) {
        ObjectNode result = objectMapper.createObjectNode(); // 使用 ObjectNode 构造 JSON。
        result.put("type", RESULT_TYPE); // 写入工具结果类型。
        result.put("success", true); // 标记执行成功。
        result.put("articleId", articleId); // 写入文章 ID。
        result.put("title", title); // 写入文章标题。
        result.put("summaryType", summaryType); // 写入总结类型。
        result.put("displayMode", summaryResult.getDisplayMode()); // 写入展示方式。
        result.put("summary", summaryResult.getSummary()); // 写入完整总结，后续 SSE summary_result 会使用。
        result.put("chatMessage", summaryResult.getChatMessage()); // 写入聊天气泡短提示。
        return result.toString(); // 返回结构化 JSON 字符串。
    }

    private String previewContent(String content) {
        if (content == null) { // null 内容无预览。
            return ""; // 返回空字符串避免日志出现 null。
        }
        String preview = content.replace('\n', ' ').replace('\r', ' '); // 去除换行，保持日志单行。
        return preview.length() <= PREVIEW_MAX_LENGTH ? preview : preview.substring(0, PREVIEW_MAX_LENGTH) + "..."; // 最多打印 80 字。
    }
}

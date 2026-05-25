package com.agent.service.impl;

import com.agent.entity.dto.SummaryRequest;
import com.agent.entity.dto.SummaryResult;
import com.agent.service.SummaryService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 通用内容总结服务实现。
 *
 * <p>适用场景：接收任意来源内容的标题、正文、总结类型和展示方式，统一完成参数标准化、内容长度限制、Prompt 构造、大模型调用和结果封装。</p>
 * <p>当前调用链：Controller/Tool/业务 Service -> SummaryService.summarize(request) -> SummaryServiceImpl -> ChatLanguageModel.generate(prompt) -> DeepSeek -> SummaryResult。</p>
 * <p>边界说明：本类只抽取通用总结能力，不新增 Tool、不修改数据库、不执行 SQL、不改 /chat/message、不触碰 ToolCallingChatService。</p>
 */
@Slf4j // 输出 [SummaryService] 前缀日志，便于排查总结链路。
@Service // 注册为 Spring Bean，供后续 Controller、Tool 或业务 Service 注入复用。
public class SummaryServiceImpl implements SummaryService { // 通用内容总结服务实现类。

    private static final int SUMMARY_CONTENT_MAX_LENGTH = 6000; // 进入总结 Prompt 的正文最大长度，避免超长内容拖垮同步模型调用。

    private static final int PREVIEW_MAX_LENGTH = 80; // 日志 preview 最大长度，禁止打印完整 content 或 summary。

    private static final String DEFAULT_SOURCE_TYPE = "TEXT"; // sourceType 缺省值，适配纯文本总结。

    private static final String DEFAULT_TITLE = "未命名内容"; // title 缺省值，避免 Prompt 中出现空标题。

    private static final String DEFAULT_SUMMARY_TYPE = "normal"; // summaryType 缺省值，生成普通摘要。

    private static final String DEFAULT_DISPLAY_MODE = "dialog"; // displayMode 缺省值，兼容原 AI 总结弹窗展示习惯。

    @Autowired // 复用 DeepSeekConfig 中已有 LangChain4j 同步模型 Bean，不新增模型客户端。
    private ChatLanguageModel chatLanguageModel; // 同步大模型，用于一次性生成完整总结正文。

    @Override // 实现 SummaryService 的统一总结入口。
    public SummaryResult summarize(SummaryRequest request) {
        if (request == null) { // request 是总结所需字段的根对象，不能为空。
            throw new IllegalArgumentException("SummaryRequest不能为空"); // 参数非法时快速失败，避免空指针进入模型调用。
        }

        String normalizedSourceType = normalizeSourceType(request.getSourceType()); // 标准化来源类型，空值兜底为 TEXT。
        String normalizedTitle = normalizeTitle(request.getTitle()); // 标准化标题，空值兜底为“未命名内容”。
        String normalizedSummaryType = normalizeSummaryType(request.getSummaryType()); // 标准化总结类型，空值或未知值兜底 normal。
        String normalizedDisplayMode = normalizeDisplayMode(request.getDisplayMode()); // 标准化展示方式，空值或未知值兜底 dialog。
        String normalizedContent = normalizeContent(request.getContent()); // 标准化正文并校验非空。
        boolean truncated = normalizedContent.length() > SUMMARY_CONTENT_MAX_LENGTH; // 记录是否触发 6000 字截断。
        String promptContent = truncated // 根据长度决定实际进入 Prompt 的正文。
                ? normalizedContent.substring(0, SUMMARY_CONTENT_MAX_LENGTH) // 超过限制时只取前 6000 字。
                : normalizedContent; // 未超过限制时使用完整正文。

        log.info("[SummaryService] summarize content"); // 标记通用总结开始。
        log.info("[SummaryService] sourceType: {}, sourceId: {}, summaryType: {}, displayMode: {}",
                normalizedSourceType, request.getSourceId(), normalizedSummaryType, normalizedDisplayMode); // 打印结构化元信息。
        log.info("[SummaryService] title: {}", normalizedTitle); // 只打印标题，不打印完整正文。
        log.info("[SummaryService] content length: {}", normalizedContent.length()); // 打印原始标准化正文长度。
        log.info("[SummaryService] content truncated: {}", truncated); // 打印是否截断。
        log.info("[SummaryService] content preview: {}", previewContent(normalizedContent)); // 只打印正文短预览。

        request.setSourceType(normalizedSourceType); // 回写标准化 sourceType，方便 buildSummaryPrompt 读取一致值。
        request.setTitle(normalizedTitle); // 回写标准化 title，方便 Prompt 和 Result 使用一致标题。
        request.setSummaryType(normalizedSummaryType); // 回写标准化 summaryType，避免后续分支重复判断空值。
        request.setDisplayMode(normalizedDisplayMode); // 回写标准化 displayMode，避免后续封装重复判断空值。

        String prompt = buildSummaryPrompt(request, promptContent, truncated); // 按 summaryType 构造具体总结 Prompt。
        String summary = chatLanguageModel.generate(prompt); // 复用现有 DeepSeek LangChain4j 同步模型生成总结。
        String normalizedSummary = summary == null ? "" : summary.trim(); // 去掉模型输出首尾空白，避免展示多余换行。
        log.info("[SummaryService] summary generated"); // 标记模型已返回总结。
        log.info("[SummaryService] summary preview: {}", previewContent(normalizedSummary)); // 只打印总结短预览。

        SummaryResult result = new SummaryResult(); // 构造结构化总结结果。
        result.setSourceType(normalizedSourceType); // 回填来源类型。
        result.setSourceId(request.getSourceId()); // 回填来源 ID。
        result.setTitle(normalizedTitle); // 回填标题。
        result.setSummaryType(normalizedSummaryType); // 回填总结类型。
        result.setDisplayMode(normalizedDisplayMode); // 回填展示方式。
        result.setSummary(normalizedSummary); // 写入完整总结正文。
        result.setChatMessage(buildChatMessage(normalizedTitle, normalizedSourceType)); // 写入聊天短提示。
        return result; // 返回结构化结果供后续工具或接口复用。
    }

    private String normalizeSourceType(String sourceType) {
        if (sourceType == null || sourceType.trim().isEmpty()) { // 空来源类型按纯文本处理。
            return DEFAULT_SOURCE_TYPE; // 返回 TEXT 兜底值。
        }
        String normalizedSourceType = sourceType.trim().toUpperCase(); // 来源类型统一使用大写，便于后续分支判断。
        return switch (normalizedSourceType) { // 只放行当前规划支持的来源类型。
            case "ARTICLE", "NOTE", "TEXT", "CRAWLER_ARTICLE", "FILE", "URL" -> normalizedSourceType; // 已支持类型直接返回。
            default -> DEFAULT_SOURCE_TYPE; // 未知来源类型兜底 TEXT，避免模型 Prompt 出现脏分类。
        };
    }

    private String normalizeTitle(String title) {
        if (title == null || title.trim().isEmpty()) { // 空标题统一兜底。
            return DEFAULT_TITLE; // 返回“未命名内容”。
        }
        return title.trim(); // 非空标题去掉首尾空白。
    }

    private String normalizeSummaryType(String summaryType) {
        if (summaryType == null || summaryType.trim().isEmpty()) { // 空总结类型使用普通摘要。
            return DEFAULT_SUMMARY_TYPE; // 返回 normal。
        }
        String normalizedSummaryType = summaryType.trim().toLowerCase(); // 总结类型统一小写。
        return switch (normalizedSummaryType) { // 只放行当前支持的三种总结类型。
            case "normal", "points", "interview" -> normalizedSummaryType; // 已支持类型直接返回。
            default -> DEFAULT_SUMMARY_TYPE; // 未知总结类型兜底 normal。
        };
    }

    private String normalizeDisplayMode(String displayMode) {
        if (displayMode == null || displayMode.trim().isEmpty()) { // 空展示方式保持弹窗默认。
            return DEFAULT_DISPLAY_MODE; // 返回 dialog。
        }
        String normalizedDisplayMode = displayMode.trim().toLowerCase(); // 展示方式统一小写。
        return switch (normalizedDisplayMode) { // 只放行当前支持的展示方式。
            case "dialog", "chat", "save" -> normalizedDisplayMode; // 已支持类型直接返回。
            default -> DEFAULT_DISPLAY_MODE; // 未知展示方式兜底 dialog。
        };
    }

    private String normalizeContent(String content) {
        if (content == null || content.trim().isEmpty()) { // content 是总结的唯一事实来源，不能为空。
            throw new IllegalArgumentException("总结内容不能为空"); // 参数非法时快速失败。
        }
        return content.trim(); // 去掉首尾空白后进入长度限制和 Prompt 构造。
    }

    private String buildSummaryPrompt(SummaryRequest request, String content) {
        return buildSummaryPrompt(request, content, content != null && content.length() >= SUMMARY_CONTENT_MAX_LENGTH); // 兼容建议方法签名并委托实际构造方法。
    }

    private String buildSummaryPrompt(SummaryRequest request, String content, boolean truncated) {
        String taskInstruction = buildSummaryTypeInstruction(request.getSummaryType()); // 根据总结类型选择输出任务说明。
        String truncateInstruction = truncated // 截断时需要明确告诉模型只看到了前 6000 字。
                ? "\n注意：原始内容超过 6000 字，以下 content 已截取前 6000 字，请只基于已提供内容总结，不要补充未出现的信息。\n" // 截断提示。
                : "\n"; // 未截断时不额外强调长度问题。
        return "你是 Tech-Brain 的通用内容总结助手。\n" + // 设定模型角色。
                "请只基于给定 content 进行总结，不要编造 content 中不存在的信息。\n" + // 明确事实边界。
                "如果 content 是代码或技术文章，请保留关键代码逻辑、核心步骤和注意事项。\n" + // 技术内容处理要求。
                "请使用中文输出，不要输出无关寒暄。\n" + // 语言和风格要求。
                "不要以“以下是总结：”之类的多余开头开始。\n" + // 去掉模板化开场。
                truncateInstruction + // 插入截断说明。
                "来源类型：" + request.getSourceType() + "\n" + // 提供来源类型给模型辅助语气。
                "标题：" + request.getTitle() + "\n\n" + // 提供标题给模型理解主题。
                "总结任务：\n" + taskInstruction + "\n\n" + // 提供 summaryType 对应任务。
                "content：\n" + content; // 放入待总结正文。
    }

    private String buildSummaryTypeInstruction(String summaryType) {
        return switch (summaryType) { // 按 summaryType 构造不同任务说明。
            case "points" -> "用项目符号输出核心要点，突出知识点、代码逻辑、关键步骤和注意事项。"; // 要点总结。
            case "interview" -> "生成适合面试表达的总结，包括：\n- 这篇内容主要讲什么\n- 我在项目中如何理解\n- 可以怎么向面试官表达\n- 可追问点"; // 面试话术。
            default -> "生成 3-5 段自然语言摘要，概括主题、核心内容、适用场景。"; // 普通摘要。
        };
    }

    private String buildChatMessage(String title, String sourceType) {
        if ("ARTICLE".equals(sourceType) || "NOTE".equals(sourceType)) { // 文章和笔记沿用“这篇笔记”的前端提示语。
            return "《" + title + "》这篇笔记总结完成。"; // 返回笔记类短提示。
        }
        return "《" + title + "》内容总结完成。"; // 返回通用内容短提示。
    }

    private String previewContent(String content) {
        if (content == null) { // null 内容没有可预览文本。
            return ""; // 返回空字符串避免日志出现 null。
        }
        String preview = content.replace('\n', ' ').replace('\r', ' '); // 去掉换行，避免日志多行膨胀。
        return preview.length() <= PREVIEW_MAX_LENGTH ? preview : preview.substring(0, PREVIEW_MAX_LENGTH) + "..."; // 最多打印 80 字。
    }
}

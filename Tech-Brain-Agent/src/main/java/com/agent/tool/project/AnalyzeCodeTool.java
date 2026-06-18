package com.agent.tool.project; // 项目代码对外 Tool 包。

import com.agent.analysis.code.CodeAnalysisHandler; // analyzeCode 内部分析器策略接口。
import com.agent.toolcalling.context.ConversationFocusContext; // 读取 recentProjectTarget 和 projectFileFocus 元信息。
import com.agent.toolcalling.context.ToolCallingContextHolder; // 获取当前 Tool Calling 请求上下文。
import com.agent.toolcalling.context.ToolCallingRequestContext; // 单轮 Tool Calling 上下文。
import com.agent.toolcalling.project.analysis.CodeAnalysisType; // P5 统一分析类型。
import com.agent.toolcalling.support.AbstractAiTool; // 仅 analyzeCode 对外继承 AiTool 基类。
import com.fasterxml.jackson.databind.JsonNode; // 解析内部 Analyzer 结果。
import com.fasterxml.jackson.databind.node.ArrayNode; // 构造 warnings 数组。
import com.fasterxml.jackson.databind.node.ObjectNode; // 构造参数 schema 和统一结果。
import lombok.extern.slf4j.Slf4j; // 记录统一分析分发日志。
import org.springframework.stereotype.Component; // 注册唯一 P5 对外分析 Tool。

import java.util.EnumMap; // 按 CodeAnalysisType 索引内部 Analyzer。
import java.util.List; // Spring 注入内部 Analyzer 列表。
import java.util.Locale; // 判断 Tool 文件路径时统一大小写。
import java.util.Map; // 保存 Analyzer 映射。

/**
 * analyzeCode 统一项目代码分析工具。
 *
 * <p>适用场景：P5.5.5 架构收敛后，项目代码分析对外只暴露本 Tool。它统一支持单文件结构分析、普通调用链分析、
 * Controller 到 Service 调用链分析、Tool 到业务组件调用链分析、前后端 SSE 事件链路分析，并通过 analysisType
 * 分发到内部 Analyzer。</p>
 *
 * <p>调用链：ToolRegistry 只注册本 AnalyzeCodeTool -> ToolCallingChatServiceImpl 对所有 P5 分析类请求构造
 * analyzeCode arguments（必须包含 analysisType）-> execute(arguments) 规范化 path/className/endpoint/toolName/eventName
 * 等目标参数 -> 分发到 CodeStructureAnalyzer / CallChainAnalyzer / ControllerServiceChainAnalyzer /
 * ToolServiceChainAnalyzer / SseEventChainAnalyzer / CodeExplanationGenerator / CodeRiskAnalyzer / CodeTestStepGenerator
 * -> 包装统一 code_analysis 外层 JSON 返回给 finalAnswer 和 tool_call_log。</p>
 *
 * <p>边界说明：本 Tool 只做静态只读分析，不修改项目文件，不访问 workspace 外路径，不接入 RAG/Milvus/向量化，
 * 已支持 P5.6 代码说明、P5.7 风险点说明和 P5.8 测试步骤生成；不实现 P5.9 开发日志保存。</p>
 */
@Slf4j // 只记录 analysisType 和相对目标，不打印源码内容。
@Component // P5 唯一对外代码分析 Tool，ToolRegistry 只暴露 analyzeCode。
public class AnalyzeCodeTool extends AbstractAiTool { // 统一项目代码分析工具。
    private static final String TOOL_NAME = "analyzeCode"; // 对外唯一 P5 分析工具名。
    private static final String RESULT_TYPE = "code_analysis"; // 统一外层结果类型。
    private static final int DEFAULT_MAX_ITEMS = 100; // 默认最多返回结果数量。
    private static final int MAX_ALLOWED_ITEMS = 300; // 最大返回结果数量。
    private static final int DEFAULT_MAX_DEPTH = 1; // 默认分析深度。
    private static final int MAX_ALLOWED_DEPTH = 2; // 当前 P5.1-P5.5 最大深度。

    private final Map<CodeAnalysisType, CodeAnalysisHandler> handlerMap; // analysisType 到内部 Analyzer 的映射。

    public AnalyzeCodeTool(List<CodeAnalysisHandler> handlers) { // Spring 注入所有内部 Analyzer。
        this.handlerMap = buildHandlerMap(handlers); // 构造只按枚举分发的映射。
    }

    @Override
    public String name() { // ToolRegistry 暴露的工具名。
        return TOOL_NAME; // 固定 analyzeCode。
    }

    @Override
    public String description() { // 给模型看的统一工具描述。
        return "统一项目代码分析工具。支持单文件结构分析、普通调用链分析、Controller 到 Service 调用链分析、Tool 到业务 Service 调用链分析、前后端 SSE 事件链路分析、代码说明、风险点说明和测试步骤生成。根据 analysisType 分发到内部分析器。只做静态只读分析，不修改项目文件。";
    }

    @Override
    public ObjectNode parametersSchema() { // 统一 analyzeCode 参数 schema。
        ObjectNode schema = createObjectSchema(); // 顶层 object schema，不设置 required。
        addProperty(schema, "analysisType", createStringProperty("分析类型，可选。AUTO、STRUCTURE、CALL_CHAIN、CONTROLLER_SERVICE、TOOL_SERVICE、SSE_EVENT_CHAIN、EXPLANATION、RISK、TEST_STEPS。默认 AUTO"), false); // 分析类型。
        addProperty(schema, "path", createStringProperty("可选，项目源码文件 workspace 相对路径"), false); // 文件路径。
        addProperty(schema, "className", createStringProperty("可选，类名，例如 SearchCodeTool、ChatMessageController"), false); // 类名。
        addProperty(schema, "methodName", createStringProperty("可选，方法名，例如 execute、sendMessage"), false); // 方法名。
        addProperty(schema, "endpoint", createStringProperty("可选，接口路径，例如 /chat/message"), false); // 接口路径。
        addProperty(schema, "toolName", createStringProperty("可选，AI Tool 名称，例如 searchCode、readProjectFile"), false); // Tool 名称。
        addProperty(schema, "eventName", createStringProperty("可选，SSE 事件名，例如 summary_result、tool_call、done"), false); // SSE 事件。
        addProperty(schema, "frontendKeyword", createStringProperty("可选，前端关键词，例如 EventSource、fetch、onmessage、addEventListener、TextDecoder"), false); // 前端关键词。
        addProperty(schema, "explanationType", createStringProperty("说明类型，可选。CLASS、METHOD、CONTROLLER_ENDPOINT、TOOL_CHAIN、CALL_CHAIN、SSE_CHAIN、AUTO。默认 AUTO"), false); // 代码说明类型。
        addProperty(schema, "detailLevel", createStringProperty("说明详细程度，可选。BRIEF、NORMAL、DETAILED。默认 NORMAL"), false); // 说明详细程度。
        addProperty(schema, "audience", createStringProperty("说明面向对象，可选。DEVELOPER、INTERVIEW、BEGINNER。默认 DEVELOPER"), false); // 说明受众。
        addProperty(schema, "riskScope", createStringProperty("风险范围，可选，仅 analysisType=RISK 时生效。AUTO、CLASS、METHOD、CONTROLLER_ENDPOINT、TOOL_CHAIN、CALL_CHAIN、SSE_CHAIN。默认 AUTO"), false); // 风险范围。
        addProperty(schema, "riskLevel", createStringProperty("风险级别过滤，可选，仅 analysisType=RISK 时生效。ALL、HIGH、MEDIUM、LOW、INFO。默认 ALL"), false); // 风险级别。
        ObjectNode riskCategoriesProperty = objectMapper.createObjectNode(); // 数组字段手动构造。
        riskCategoriesProperty.put("type", "array"); // 数组类型。
        ObjectNode riskCategoriesItems = objectMapper.createObjectNode(); // 数组元素类型。
        riskCategoriesItems.put("type", "string"); // 元素为字符串。
        riskCategoriesProperty.set("items", riskCategoriesItems); // 设置元素类型。
        riskCategoriesProperty.put("description", "风险类型过滤，可选，仅 analysisType=RISK 时生效，例如 SECURITY、PATH_TRAVERSAL、SSE_STREAM、TOOL_CALLING"); // 字段说明。
        addProperty(schema, "riskCategories", riskCategoriesProperty, false); // 风险类型过滤。
        ObjectNode includeEvidenceProperty = objectMapper.createObjectNode(); // boolean 字段手动创建。
        includeEvidenceProperty.put("type", "boolean"); // boolean 类型。
        includeEvidenceProperty.put("description", "是否返回风险证据，可选，仅 analysisType=RISK 时生效，默认 true"); // 字段说明。
        addProperty(schema, "includeEvidence", includeEvidenceProperty, false); // 风险证据开关。
        ObjectNode includeSuggestionProperty = objectMapper.createObjectNode(); // boolean 字段手动创建。
        includeSuggestionProperty.put("type", "boolean"); // boolean 类型。
        includeSuggestionProperty.put("description", "是否返回简短处理建议，可选，仅 analysisType=RISK 时生效，默认 true；只给说明级建议，不生成修改方案或 patch"); // 字段说明。
        addProperty(schema, "includeSuggestion", includeSuggestionProperty, false); // 风险建议开关。
        addProperty(schema, "testScope", createStringProperty("测试范围，可选，仅 analysisType=TEST_STEPS 时生效。AUTO、CLASS、METHOD、CONTROLLER_ENDPOINT、TOOL_CHAIN、CALL_CHAIN、SSE_CHAIN。默认 AUTO"), false); // 测试范围。
        addProperty(schema, "testType", createStringProperty("测试类型，可选，仅 analysisType=TEST_STEPS 时生效。ALL、MANUAL、API、TOOL_CALLING、SSE、REGRESSION、SECURITY、LOG。默认 ALL"), false); // 测试类型。
        ObjectNode includeRiskCasesProperty = objectMapper.createObjectNode(); // boolean 字段手动创建。
        includeRiskCasesProperty.put("type", "boolean"); // boolean 类型。
        includeRiskCasesProperty.put("description", "是否结合风险点生成测试场景，可选，仅 analysisType=TEST_STEPS 时生效，默认 true"); // 字段说明。
        addProperty(schema, "includeRiskCases", includeRiskCasesProperty, false); // 风险场景开关。
        ObjectNode includeLogChecksProperty = objectMapper.createObjectNode(); // boolean 字段手动创建。
        includeLogChecksProperty.put("type", "boolean"); // boolean 类型。
        includeLogChecksProperty.put("description", "是否包含 tool_call_log / 后端日志验证，可选，仅 analysisType=TEST_STEPS 时生效，默认 true"); // 字段说明。
        addProperty(schema, "includeLogChecks", includeLogChecksProperty, false); // 日志验证开关。
        ObjectNode includeExpectedResultProperty = objectMapper.createObjectNode(); // boolean 字段手动创建。
        includeExpectedResultProperty.put("type", "boolean"); // boolean 类型。
        includeExpectedResultProperty.put("description", "是否包含预期结果，可选，仅 analysisType=TEST_STEPS 时生效，默认 true"); // 字段说明。
        addProperty(schema, "includeExpectedResult", includeExpectedResultProperty, false); // 预期结果开关。
        ObjectNode includeRequestExampleProperty = objectMapper.createObjectNode(); // boolean 字段手动创建。
        includeRequestExampleProperty.put("type", "boolean"); // boolean 类型。
        includeRequestExampleProperty.put("description", "是否包含请求示例，可选，仅 analysisType=TEST_STEPS 时生效，默认 true"); // 字段说明。
        addProperty(schema, "includeRequestExample", includeRequestExampleProperty, false); // 请求示例开关。
        addProperty(schema, "maxDepth", createIntegerProperty("分析深度，可选，默认 1，最大 2"), false); // 深度。
        ObjectNode includeSnippetProperty = objectMapper.createObjectNode(); // boolean 字段手动创建。
        includeSnippetProperty.put("type", "boolean"); // boolean 类型。
        includeSnippetProperty.put("description", "是否包含少量代码片段，可选；结构/链路分析默认 true，EXPLANATION/RISK/TEST_STEPS 默认 false"); // 片段说明。
        addProperty(schema, "includeSnippet", includeSnippetProperty, false); // 片段开关。
        addProperty(schema, "maxItems", createIntegerProperty("最多返回结果数量，可选，默认 100，最大 300"), false); // 结果上限。
        return schema; // 返回完整 schema。
    }

    @Override
    public String execute(JsonNode arguments) { // 统一分析执行入口。
        ObjectNode normalizedArguments = normalizeArguments(arguments); // 复制并补齐默认参数。
        CodeAnalysisType requestedType = CodeAnalysisType.from(getOptionalText(normalizedArguments, "analysisType", "AUTO")); // 解析 analysisType。
        CodeAnalysisType effectiveType = requestedType == CodeAnalysisType.AUTO
                ? inferAnalysisType(normalizedArguments)
                : requestedType; // AUTO 时按参数推断，否则使用显式类型。
        normalizedArguments.put("analysisType", effectiveType.name()); // 写入最终 analysisType，保证内部和日志一致。
        applyIncludeSnippetDefault(normalizedArguments, effectiveType); // EXPLANATION 默认不带代码片段，其它分析保持少量片段。
        applyProjectFileFocusIfNecessary(normalizedArguments); // 没有明确目标时优先使用 recentProjectTarget，再回退 projectFileFocus。
        normalizeClassNameFallback(normalizedArguments, effectiveType); // 对需要 path 的分析器，用 className 兜底成 path。
        if (!hasAnyTarget(normalizedArguments)) { // 没有明确目标且没有 projectFileFocus。
            return buildUnifiedFailureResult(effectiveType, normalizedArguments,
                    "请指定要分析的类名、文件、接口、Tool 或 SSE 事件。").toString(); // 返回清晰失败。
        }

        CodeAnalysisHandler handler = handlerMap.get(effectiveType); // 按 analysisType 获取内部 Analyzer。
        if (handler == null) { // 理论上启动时应注入完整 Analyzer。
            return buildUnifiedFailureResult(effectiveType, normalizedArguments,
                    "当前分析类型没有可用内部 Analyzer。").toString(); // 返回失败。
        }

        try {
            log.info("[AnalyzeCodeTool] dispatch analysisType: {}, target: {}", effectiveType, previewTarget(normalizedArguments)); // 只打印轻量目标。
            String innerResultJson = handler.analyze(normalizedArguments); // 执行内部 Analyzer。
            JsonNode innerResult = objectMapper.readTree(innerResultJson == null || innerResultJson.isBlank() ? "{}" : innerResultJson); // 解析内部结果。
            return buildUnifiedResult(effectiveType, normalizedArguments, innerResult).toString(); // 包装统一外层。
        } catch (Exception e) {
            log.error("[AnalyzeCodeTool] analyze dispatch failed, analysisType: {}", effectiveType, e); // 保留堆栈供排查。
            return buildUnifiedFailureResult(effectiveType, normalizedArguments, "代码分析失败，请稍后重试。").toString(); // 统一兜底失败。
        }
    }

    private Map<CodeAnalysisType, CodeAnalysisHandler> buildHandlerMap(List<CodeAnalysisHandler> handlers) { // 构建内部 Analyzer 映射。
        Map<CodeAnalysisType, CodeAnalysisHandler> map = new EnumMap<>(CodeAnalysisType.class); // 使用枚举 Map。
        if (handlers == null) { // 没有注入任何 Analyzer。
            return map; // 返回空映射，执行时会给出明确错误。
        }
        for (CodeAnalysisHandler handler : handlers) { // 遍历 Spring Bean。
            if (handler != null && handler.analysisType() != null && handler.analysisType() != CodeAnalysisType.AUTO) { // AUTO 不作为真实处理器。
                map.put(handler.analysisType(), handler); // 同类覆盖由 Spring 配置保证唯一，后续可加启动校验。
            }
        }
        return map; // 返回映射。
    }

    private ObjectNode normalizeArguments(JsonNode arguments) { // 复制 arguments 并补齐通用默认参数。
        ObjectNode normalized = arguments instanceof ObjectNode objectNode
                ? objectNode.deepCopy()
                : objectMapper.createObjectNode(); // 只接受 object，非 object 按空参数。
        if (!normalized.hasNonNull("maxDepth")) { // 缺少深度时默认 1。
            normalized.put("maxDepth", DEFAULT_MAX_DEPTH); // 写入默认深度。
        } else {
            normalized.put("maxDepth", clamp(normalized.path("maxDepth").asInt(DEFAULT_MAX_DEPTH), DEFAULT_MAX_DEPTH, MAX_ALLOWED_DEPTH)); // 限制深度。
        }
        if (!normalized.hasNonNull("maxItems")) { // 缺少数量上限。
            normalized.put("maxItems", DEFAULT_MAX_ITEMS); // 写入默认上限。
        } else {
            normalized.put("maxItems", clamp(normalized.path("maxItems").asInt(DEFAULT_MAX_ITEMS), 1, MAX_ALLOWED_ITEMS)); // 限制上限。
        }
        return normalized; // 返回规范化参数。
    }

    private CodeAnalysisType inferAnalysisType(ObjectNode arguments) { // AUTO 模式下按参数推断分析类型。
        if (hasTestStepParams(arguments)) { // 出现 testScope/testType/includeRiskCases 等测试步骤参数时进入测试步骤生成。
            return CodeAnalysisType.TEST_STEPS; // P5.8 测试步骤。
        }
        if (hasRiskParams(arguments)) { // 出现 riskScope/riskLevel/riskCategories 等风险参数时进入风险分析。
            return CodeAnalysisType.RISK; // P5.7 风险点说明。
        }
        if (hasText(arguments, "explanationType") || hasText(arguments, "detailLevel") || hasText(arguments, "audience")) { // 说明参数出现时进入说明生成。
            return CodeAnalysisType.EXPLANATION; // 代码说明。
        }
        if (hasText(arguments, "eventName") || hasText(arguments, "frontendKeyword")) { // SSE 事件或前端关键词最明确。
            return CodeAnalysisType.SSE_EVENT_CHAIN; // SSE 链路分析。
        }
        if (hasText(arguments, "endpoint")) { // 只有 endpoint 时默认后端 Controller→Service。
            return CodeAnalysisType.CONTROLLER_SERVICE; // Controller 专项。
        }
        if (hasText(arguments, "toolName")) { // 明确 toolName 时分析 Tool→Service。
            return CodeAnalysisType.TOOL_SERVICE; // Tool 专项。
        }
        if (hasText(arguments, "methodName")) { // 只有方法名通常是调用链。
            return CodeAnalysisType.CALL_CHAIN; // 普通调用链。
        }
        String pathOrClass = firstText(arguments, "path", "className"); // 读取 path 或 className。
        if (looksLikeToolTarget(pathOrClass)) { // 明确 Tool 类/文件但未指定类型时默认结构分析，避免把“有哪些方法”误转专项。
            return CodeAnalysisType.STRUCTURE; // 单文件结构。
        }
        return CodeAnalysisType.STRUCTURE; // 只有 path/className 时默认结构分析。
    }

    private void applyProjectFileFocusIfNecessary(ObjectNode arguments) { // 没有明确目标时才允许使用 recentProjectTarget/projectFileFocus。
        if (hasAnyTarget(arguments)) { // 已有明确目标。
            return; // 不被 focus 锁死。
        }
        ToolCallingRequestContext context = ToolCallingContextHolder.get(); // 读取当前请求上下文。
        ConversationFocusContext recentTarget = context == null ? null : context.getRecentProjectTarget(); // 读取最近明确项目目标。
        if (recentTarget != null && recentTarget.getPath() != null && !recentTarget.getPath().isBlank()) { // 有最近明确项目目标。
            arguments.put("path", recentTarget.getPath()); // 只写入相对 workspace 路径，不保存内容。
            arguments.put("targetSource", "recentProjectTarget"); // 标记来源，便于 tool_call_log route_reason 排查。
            return; // recentProjectTarget 优先于 projectFileFocus。
        }
        ConversationFocusContext focus = context == null ? null : context.getProjectFileFocus(); // 读取项目文件焦点。
        if (focus != null && focus.getPath() != null && !focus.getPath().isBlank()) { // 有有效 projectFileFocus。
            arguments.put("path", focus.getPath()); // 只写入相对 workspace 路径，不保存内容。
            arguments.put("targetSource", "projectFileFocus"); // 标记来源，便于路由日志排查。
        }
    }

    private void normalizeClassNameFallback(ObjectNode arguments, CodeAnalysisType analysisType) { // 将 className 转成旧分析器可理解的 path 兜底。
        if (hasText(arguments, "path") || !hasText(arguments, "className")) { // path 已存在或没有 className。
            return; // 不处理。
        }
        if (analysisType == CodeAnalysisType.STRUCTURE
                || analysisType == CodeAnalysisType.CALL_CHAIN
                || analysisType == CodeAnalysisType.CONTROLLER_SERVICE
                || analysisType == CodeAnalysisType.EXPLANATION
                || analysisType == CodeAnalysisType.RISK
                || analysisType == CodeAnalysisType.TEST_STEPS) { // 这些分析器通过 path 字段支持类名唯一定位。
            arguments.put("path", arguments.path("className").asText()); // className 作为文件名/类名传给内部解析器。
        }
    }

    private void applyIncludeSnippetDefault(ObjectNode arguments, CodeAnalysisType analysisType) { // 根据分析类型补齐 includeSnippet 默认值。
        if (arguments == null || arguments.hasNonNull("includeSnippet")) { // 用户已显式传值时不覆盖。
            return; // 保留用户选择。
        }
        arguments.put("includeSnippet", analysisType != CodeAnalysisType.EXPLANATION
                && analysisType != CodeAnalysisType.RISK
                && analysisType != CodeAnalysisType.TEST_STEPS); // 说明、风险和测试步骤默认 false，其它分析默认 true。
    }

    private boolean hasTestStepParams(ObjectNode arguments) { // 判断是否出现测试步骤专属参数。
        return hasText(arguments, "testScope")
                || hasText(arguments, "testType")
                || (arguments != null && (arguments.hasNonNull("includeRiskCases")
                || arguments.hasNonNull("includeLogChecks")
                || arguments.hasNonNull("includeExpectedResult")
                || arguments.hasNonNull("includeRequestExample"))); // 任一测试参数即视为测试步骤意图。
    }

    private boolean hasRiskParams(ObjectNode arguments) { // 判断是否出现风险分析专属参数。
        return hasText(arguments, "riskScope")
                || hasText(arguments, "riskLevel")
                || (arguments != null && arguments.path("riskCategories").isArray() && arguments.path("riskCategories").size() > 0); // 任一风险参数即视为风险意图。
    }

    private ObjectNode buildUnifiedResult(CodeAnalysisType analysisType, ObjectNode arguments, JsonNode innerResult) { // 包装统一外层结果。
        ObjectNode result = objectMapper.createObjectNode(); // 创建外层对象。
        boolean success = innerResult.path("success").asBoolean(false); // 继承内部成功状态。
        result.put("type", RESULT_TYPE); // 统一外层 type。
        result.put("success", success); // 统一成功状态。
        result.put("analysisType", analysisType.name()); // 写入分析类型。
        result.set("target", buildTarget(arguments, innerResult)); // 写入目标信息。
        result.set("result", innerResult); // 保留内部 Analyzer 原结果。
        copyFocusFields(result, innerResult); // 复制 path/fileName/language 等字段，兼容 projectFileFocus 逻辑。
        if (!success) { // 失败时透传内部失败消息。
            result.put("message", innerResult.path("message").asText("未找到真实匹配目标，无法分析。")); // 失败原因。
        }
        ArrayNode warnings = objectMapper.createArrayNode(); // 统一 warnings。
        if (innerResult.has("warnings") && innerResult.path("warnings").isArray()) { // 内部已有 warnings。
            innerResult.path("warnings").forEach(warnings::add); // 复制内部 warnings。
        }
        result.set("warnings", warnings); // 写入 warnings 数组。
        return result; // 返回统一外层。
    }

    private ObjectNode buildUnifiedFailureResult(CodeAnalysisType analysisType, ObjectNode arguments, String message) { // 构造 analyzeCode 外层失败结果。
        ObjectNode result = objectMapper.createObjectNode(); // 创建结果。
        result.put("type", RESULT_TYPE); // 外层 type。
        result.put("success", false); // 标记失败。
        result.put("analysisType", analysisType == null ? CodeAnalysisType.AUTO.name() : analysisType.name()); // 写入类型。
        result.set("target", buildTarget(arguments, objectMapper.createObjectNode())); // 写入已知目标。
        result.put("message", message); // 失败原因。
        result.set("warnings", objectMapper.createArrayNode()); // 空 warnings。
        return result; // 返回失败。
    }

    private ObjectNode buildTarget(ObjectNode arguments, JsonNode innerResult) { // 构造统一 target。
        ObjectNode target = objectMapper.createObjectNode(); // 创建 target。
        putIfText(target, "path", firstText(arguments, innerResult, "path")); // path。
        putIfText(target, "className", firstText(arguments, innerResult, "className")); // className。
        putIfText(target, "methodName", firstText(arguments, innerResult, "methodName")); // methodName。
        putIfText(target, "endpoint", firstText(arguments, innerResult, "endpoint")); // endpoint。
        putIfText(target, "toolName", firstText(arguments, innerResult, "toolName")); // toolName。
        putIfText(target, "eventName", firstText(arguments, innerResult, "eventName")); // eventName。
        putIfText(target, "frontendKeyword", firstText(arguments, innerResult, "frontendKeyword")); // frontendKeyword。
        return target; // 返回 target。
    }

    private void copyFocusFields(ObjectNode result, JsonNode innerResult) { // 复制焦点保存需要的常用字段。
        copyText(result, innerResult, "path"); // workspace 相对路径。
        copyText(result, innerResult, "fileName"); // 文件名。
        copyText(result, innerResult, "extension"); // 扩展名。
        copyText(result, innerResult, "language"); // 语言展示名。
        if (innerResult.has("fileSize")) { // 文件大小存在。
            result.set("fileSize", innerResult.get("fileSize")); // 复制文件大小。
        }
        if (innerResult.has("truncated")) { // 截断状态存在。
            result.set("truncated", innerResult.get("truncated")); // 复制截断状态。
        }
    }

    private boolean hasAnyTarget(ObjectNode arguments) { // 判断是否存在任一分析目标。
        return hasText(arguments, "path")
                || hasText(arguments, "className")
                || hasText(arguments, "methodName")
                || hasText(arguments, "endpoint")
                || hasText(arguments, "toolName")
                || hasText(arguments, "eventName")
                || hasText(arguments, "frontendKeyword"); // 任一目标字段非空即可执行。
    }

    private boolean hasText(ObjectNode arguments, String fieldName) { // 判断字段是否为非空文本。
        return arguments != null && arguments.hasNonNull(fieldName) && !arguments.path(fieldName).asText("").isBlank(); // 非空白。
    }

    private String firstText(ObjectNode arguments, String... fields) { // 从 arguments 取第一个非空字段。
        if (arguments == null || fields == null) { // 空输入。
            return ""; // 返回空。
        }
        for (String field : fields) { // 遍历字段。
            String value = arguments.path(field).asText(""); // 读取字段。
            if (value != null && !value.isBlank()) { // 命中非空。
                return value; // 返回值。
            }
        }
        return ""; // 没有非空。
    }

    private String firstText(ObjectNode arguments, JsonNode innerResult, String field) { // 从 arguments 和内部结果中取目标字段。
        String fromArguments = arguments == null ? "" : arguments.path(field).asText(""); // 优先参数。
        if (fromArguments != null && !fromArguments.isBlank()) { // 参数有值。
            return fromArguments; // 返回参数值。
        }
        String fromInner = innerResult == null ? "" : innerResult.path(field).asText(""); // 再读内部结果。
        return fromInner == null ? "" : fromInner; // 返回内部值。
    }

    private void putIfText(ObjectNode node, String fieldName, String value) { // 非空时写入字段。
        if (value != null && !value.isBlank()) { // 有值才写。
            node.put(fieldName, value); // 写入文本。
        }
    }

    private void copyText(ObjectNode target, JsonNode source, String fieldName) { // 复制文本字段。
        String value = source == null ? "" : source.path(fieldName).asText(""); // 读取来源。
        if (value != null && !value.isBlank()) { // 有值才复制。
            target.put(fieldName, value); // 写入目标。
        }
    }

    private int clamp(int value, int min, int max) { // 限制整数范围。
        return Math.max(min, Math.min(max, value)); // 返回夹紧后的值。
    }

    private boolean looksLikeToolTarget(String pathOrClass) { // 判断目标文本是否像 AI Tool 类/文件。
        if (pathOrClass == null || pathOrClass.isBlank()) { // 空值。
            return false; // 不像。
        }
        String lower = pathOrClass.replace('\\', '/').toLowerCase(Locale.ROOT); // 统一路径和大小写。
        return lower.endsWith("tool.java") || lower.endsWith("tool") || lower.contains("/tool/"); // Tool 文件/类/目录。
    }

    private String previewTarget(ObjectNode arguments) { // 构造安全日志目标预览。
        return buildTarget(arguments, objectMapper.createObjectNode()).toString(); // 只含路径、类名、endpoint 等短字段。
    }
}

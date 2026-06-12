package com.agent.analysis.code; // 项目代码内部分析器包。

import com.agent.toolcalling.project.analysis.CodeAnalysisType; // analyzeCode 统一分析类型。
import com.agent.toolcalling.project.analysis.CodeExplanationType; // P5.6 代码说明类型。
import com.fasterxml.jackson.databind.JsonNode; // 读取底层 Analyzer 返回的真实分析结果。
import com.fasterxml.jackson.databind.node.ArrayNode; // 构造说明条目数组。
import com.fasterxml.jackson.databind.node.ObjectNode; // 构造 code_explanation JSON。
import org.springframework.stereotype.Component; // 注册为内部 Spring Bean，供 AnalyzeCodeTool 分发使用。

import java.util.ArrayList; // 汇总证据和调用描述。
import java.util.List; // 轻量列表结构。
import java.util.Locale; // 解析 detailLevel/audience。

/**
 * P5.6 代码说明内部生成器。
 *
 * <p>适用场景：用户要求“说明/解释/面试怎么讲/这个类是干嘛的”时，对外仍然只调用 analyzeCode，
 * AnalyzeCodeTool 根据 analysisType=EXPLANATION 分发到本组件。本组件不会注册为 AI Tool，而是复用已有
 * CodeStructureAnalyzer、CallChainAnalyzer、ControllerServiceChainAnalyzer、ToolServiceChainAnalyzer 和
 * SseEventChainAnalyzer 的真实扫描结果，整理成面向开发者、面试或新手的结构化代码说明。</p>
 *
 * <p>调用链：ToolCallingChatServiceImpl 构造 analyzeCode arguments（analysisType=EXPLANATION）
 * -> AnalyzeCodeTool 规范化 recentProjectTarget/projectFileFocus 和目标参数 -> CodeExplanationGenerator.execute(arguments)
 * -> 根据 explanationType 调用已有内部 Analyzer -> 汇总 responsibilities/mainFlow/keyMethods/dependencies/sourceEvidence
 * -> 返回 code_explanation 作为 code_analysis.result。</p>
 *
 * <p>边界说明：本组件只基于真实扫描结果生成说明，不输出完整源码，不生成风险分析、测试步骤、修改建议或 patch；
 * 未扫描到真实类、接口、Tool 或事件时返回失败，不根据命名猜测不存在的链路。</p>
 */
@Component // 仅作为 analyzeCode 内部 Handler 注入，不会被 ToolRegistry 暴露为对外 Tool。
public class CodeExplanationGenerator extends AbstractCodeAnalysisHandler { // P5.6 代码说明内部生成器。
    private static final String RESULT_TYPE = "code_explanation"; // 内层结果类型。
    private static final int MAX_EVIDENCE_ITEMS = 20; // sourceEvidence 最多返回 20 条，避免刷屏。
    private static final int MAX_TEXT_ITEMS = 12; // 每个说明数组最多返回 12 条。

    private final CodeStructureAnalyzer codeStructureAnalyzer; // 复用单文件结构分析能力。
    private final CallChainAnalyzer callChainAnalyzer; // 复用普通调用链分析能力。
    private final ControllerServiceChainAnalyzer controllerServiceChainAnalyzer; // 复用 Controller→Service 分析能力。
    private final ToolServiceChainAnalyzer toolServiceChainAnalyzer; // 复用 Tool→业务组件分析能力。
    private final SseEventChainAnalyzer sseEventChainAnalyzer; // 复用 SSE 事件链路分析能力。

    public CodeExplanationGenerator(CodeStructureAnalyzer codeStructureAnalyzer,
                                    CallChainAnalyzer callChainAnalyzer,
                                    ControllerServiceChainAnalyzer controllerServiceChainAnalyzer,
                                    ToolServiceChainAnalyzer toolServiceChainAnalyzer,
                                    SseEventChainAnalyzer sseEventChainAnalyzer) { // 注入已有内部 Analyzer，避免重写扫描逻辑。
        this.codeStructureAnalyzer = codeStructureAnalyzer; // 保存结构分析器。
        this.callChainAnalyzer = callChainAnalyzer; // 保存调用链分析器。
        this.controllerServiceChainAnalyzer = controllerServiceChainAnalyzer; // 保存 Controller 专项分析器。
        this.toolServiceChainAnalyzer = toolServiceChainAnalyzer; // 保存 Tool 专项分析器。
        this.sseEventChainAnalyzer = sseEventChainAnalyzer; // 保存 SSE 专项分析器。
    }

    @Override
    public CodeAnalysisType analysisType() { // 返回 AnalyzeCodeTool 分发键。
        return CodeAnalysisType.EXPLANATION; // P5.6 说明生成。
    }

    @Override
    public String execute(JsonNode arguments) { // 说明生成入口。
        ObjectNode request = copyArguments(arguments); // 复制入参，避免影响外层 tool_call_log。
        CodeExplanationType explanationType = resolveExplanationType(request); // 解析或自动判断说明类型。
        DetailLevel detailLevel = DetailLevel.from(getOptionalText(request, "detailLevel", "NORMAL")); // 解析详细程度。
        Audience audience = Audience.from(getOptionalText(request, "audience", "DEVELOPER")); // 解析面向对象。
        prepareAnalyzerArguments(request, explanationType); // 设置说明场景默认参数，并让 className 可被旧 Analyzer 定位。

        AnalysisBundle bundle = runAnalysis(explanationType, request); // 调用已有 Analyzer 获取真实分析结果。
        if (!bundle.primarySuccess()) { // 底层真实扫描没有命中目标。
            return buildFailureResult(explanationType, audience, detailLevel, request, bundle).toString(); // 不捏造说明。
        }

        ObjectNode result = objectMapper.createObjectNode(); // 构造 code_explanation。
        result.put("type", RESULT_TYPE); // 写入内层 type。
        result.put("success", true); // 标记成功。
        result.put("explanationType", explanationType.name()); // 写入最终说明类型。
        result.put("audience", audience.name()); // 写入面向对象。
        result.put("detailLevel", detailLevel.name()); // 写入详细程度。
        copyTargetFields(result, request, bundle.primaryResult()); // 复制 path/className/endpoint/toolName/eventName 等轻量目标字段。

        ArrayNode responsibilities = objectMapper.createArrayNode(); // 职责说明。
        ArrayNode mainFlow = objectMapper.createArrayNode(); // 主流程说明。
        ArrayNode keyMethods = objectMapper.createArrayNode(); // 关键方法说明。
        ArrayNode dependencies = objectMapper.createArrayNode(); // 依赖组件说明。
        ArrayNode callChainExplanation = objectMapper.createArrayNode(); // 调用链说明。
        ArrayNode sseFlowExplanation = objectMapper.createArrayNode(); // SSE 链路说明。
        ArrayNode importantDetails = objectMapper.createArrayNode(); // 重要细节。
        ArrayNode sourceEvidence = objectMapper.createArrayNode(); // 真实来源证据。
        ArrayNode warnings = objectMapper.createArrayNode(); // 说明限制。

        fillExplanationArrays(explanationType, audience, detailLevel, bundle, responsibilities, mainFlow,
                keyMethods, dependencies, callChainExplanation, sseFlowExplanation, importantDetails, sourceEvidence); // 从真实分析结果整理说明数组。
        warnings.add("当前说明基于项目源码静态扫描结果生成，不输出完整源码，不包含风险分析、测试步骤或修改建议。"); // 明确边界。
        copyWarnings(bundle.primaryResult(), warnings); // 透传底层 Analyzer 的不确定项。

        result.put("summary", buildSummary(explanationType, audience, bundle.primaryResult())); // 一句话概览。
        result.set("responsibilities", trimArray(responsibilities, detailLimit(detailLevel))); // 写入职责。
        result.set("mainFlow", trimArray(mainFlow, detailLimit(detailLevel))); // 写入主流程。
        result.set("keyMethods", trimArray(keyMethods, detailLimit(detailLevel))); // 写入关键方法。
        result.set("dependencies", trimArray(dependencies, detailLimit(detailLevel))); // 写入依赖。
        result.set("callChainExplanation", trimArray(callChainExplanation, detailLimit(detailLevel))); // 写入调用链说明。
        result.set("sseFlowExplanation", trimArray(sseFlowExplanation, detailLimit(detailLevel))); // 写入 SSE 说明。
        result.set("importantDetails", trimArray(importantDetails, detailLimit(detailLevel))); // 写入重要细节。
        result.set("sourceEvidence", trimArray(sourceEvidence, MAX_EVIDENCE_ITEMS)); // 写入证据来源。
        result.set("warnings", warnings); // 写入 warnings。
        return result.toString(); // 返回内层 JSON 字符串。
    }

    private ObjectNode copyArguments(JsonNode arguments) { // 复制工具参数。
        return arguments instanceof ObjectNode objectNode
                ? objectNode.deepCopy()
                : objectMapper.createObjectNode(); // 非 object 入参按空对象处理。
    }

    private CodeExplanationType resolveExplanationType(ObjectNode request) { // 解析说明类型。
        CodeExplanationType requestedType = CodeExplanationType.from(getOptionalText(request, "explanationType", "AUTO")); // 读取用户/路由指定值。
        if (requestedType != CodeExplanationType.AUTO) { // 显式类型优先。
            return requestedType; // 返回显式类型。
        }
        if (hasText(request, "eventName") || hasText(request, "frontendKeyword")) { // SSE 事件或前端关键词最明确。
            return CodeExplanationType.SSE_CHAIN; // 说明 SSE 链路。
        }
        if (hasText(request, "endpoint")) { // endpoint 说明默认走接口流程。
            return CodeExplanationType.CONTROLLER_ENDPOINT; // 说明 Controller 接口。
        }
        if (hasText(request, "toolName") || looksLikeToolTarget(firstText(request, "path", "className"))) { // Tool 名或 Tool 文件。
            return CodeExplanationType.TOOL_CHAIN; // 说明 Tool 执行链路。
        }
        if (hasText(request, "methodName")) { // 有方法名时默认说明方法。
            return CodeExplanationType.METHOD; // 说明方法。
        }
        return CodeExplanationType.CLASS; // 兜底为类/文件说明。
    }

    private void prepareAnalyzerArguments(ObjectNode request, CodeExplanationType explanationType) { // 设置内部 Analyzer 入参默认值。
        request.put("analysisType", mappedAnalysisType(explanationType).name()); // 底层 Analyzer 只看到它负责的分析类型。
        if (!request.hasNonNull("includeSnippet")) { // 说明生成默认不贴代码片段。
            request.put("includeSnippet", false); // 降低完整源码泄漏风险。
        }
        if (!request.hasNonNull("maxItems")) { // 缺少上限时使用 100。
            request.put("maxItems", 100); // 保持与 P5 其它分析一致。
        }
        if (!request.hasNonNull("maxDepth")) { // 缺少深度时使用 1。
            request.put("maxDepth", 1); // 第一版只做轻量说明。
        }
        if (!hasText(request, "path") && hasText(request, "className")
                && (explanationType == CodeExplanationType.CLASS
                || explanationType == CodeExplanationType.METHOD
                || explanationType == CodeExplanationType.CALL_CHAIN
                || explanationType == CodeExplanationType.CONTROLLER_ENDPOINT)) { // 旧 Analyzer 通过 path 字段支持类名定位。
            request.put("path", request.path("className").asText()); // 将 className 作为定位目标传入。
        }
    }

    private AnalysisBundle runAnalysis(CodeExplanationType explanationType, ObjectNode request) { // 执行底层真实分析。
        try {
            JsonNode primary = readJson(analyzerFor(explanationType).analyze(request)); // 调用当前说明类型对应 Analyzer。
            JsonNode secondary = objectMapper.createObjectNode(); // 默认没有辅助分析。
            if (explanationType == CodeExplanationType.METHOD && primary.path("success").asBoolean(false)) { // 方法说明额外看调用链。
                ObjectNode callRequest = request.deepCopy(); // 构造调用链辅助请求。
                callRequest.put("analysisType", CodeAnalysisType.CALL_CHAIN.name()); // 指定调用链。
                secondary = readJson(callChainAnalyzer.analyze(callRequest)); // 获取方法内部调用。
            }
            return new AnalysisBundle(primary, secondary); // 返回两个结果。
        } catch (Exception e) {
            ObjectNode failure = objectMapper.createObjectNode(); // 构造失败结果。
            failure.put("type", "analysis_failure"); // 标记内部失败。
            failure.put("success", false); // 执行失败。
            failure.put("message", "未找到真实匹配目标，无法生成代码说明。"); // 不暴露内部异常。
            return new AnalysisBundle(failure, objectMapper.createObjectNode()); // 返回失败包。
        }
    }

    private CodeAnalysisHandler analyzerFor(CodeExplanationType explanationType) { // 说明类型映射到底层 Analyzer。
        return switch (explanationType) { // 复用已有 P5 分析能力。
            case CLASS -> codeStructureAnalyzer; // 类/文件说明基于结构分析。
            case METHOD, CALL_CHAIN -> callChainAnalyzer; // 方法和普通调用链说明基于调用链。
            case CONTROLLER_ENDPOINT -> controllerServiceChainAnalyzer; // 接口说明基于 Controller→Service。
            case TOOL_CHAIN -> toolServiceChainAnalyzer; // Tool 说明基于 Tool→Service。
            case SSE_CHAIN -> sseEventChainAnalyzer; // SSE 说明基于 SSE 链路。
            case AUTO -> codeStructureAnalyzer; // 理论不会进入，兜底结构说明。
        };
    }

    private CodeAnalysisType mappedAnalysisType(CodeExplanationType explanationType) { // 说明类型映射到底层分析类型。
        return switch (explanationType) { // 与 analyzerFor 保持一致。
            case CLASS -> CodeAnalysisType.STRUCTURE; // 结构。
            case METHOD, CALL_CHAIN -> CodeAnalysisType.CALL_CHAIN; // 调用链。
            case CONTROLLER_ENDPOINT -> CodeAnalysisType.CONTROLLER_SERVICE; // Controller→Service。
            case TOOL_CHAIN -> CodeAnalysisType.TOOL_SERVICE; // Tool→Service。
            case SSE_CHAIN -> CodeAnalysisType.SSE_EVENT_CHAIN; // SSE 链路。
            case AUTO -> CodeAnalysisType.STRUCTURE; // 兜底。
        };
    }

    private JsonNode readJson(String json) throws Exception { // 安全解析 Analyzer 结果。
        return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json); // 空结果按空对象处理。
    }

    private ObjectNode buildFailureResult(CodeExplanationType explanationType,
                                          Audience audience,
                                          DetailLevel detailLevel,
                                          ObjectNode request,
                                          AnalysisBundle bundle) { // 构造说明失败 JSON。
        ObjectNode failure = objectMapper.createObjectNode(); // 创建失败对象。
        JsonNode primary = bundle == null ? objectMapper.createObjectNode() : bundle.primaryResult(); // 读取底层结果。
        failure.put("type", RESULT_TYPE); // 内层 type 仍为 code_explanation。
        failure.put("success", false); // 标记失败。
        failure.put("explanationType", explanationType.name()); // 写入说明类型。
        failure.put("audience", audience.name()); // 写入面向对象。
        failure.put("detailLevel", detailLevel.name()); // 写入详细程度。
        copyTargetFields(failure, request, primary); // 复制已知目标。
        failure.put("message", primary.path("message").asText("未找到真实匹配目标，无法生成代码说明。")); // 透传底层失败原因。
        copyCandidateFields(failure, primary); // 复制真实候选，不捏造结果。
        failure.set("warnings", objectMapper.createArrayNode()); // 失败时 warnings 为空。
        return failure; // 返回失败 JSON。
    }

    private void fillExplanationArrays(CodeExplanationType explanationType,
                                       Audience audience,
                                       DetailLevel detailLevel,
                                       AnalysisBundle bundle,
                                       ArrayNode responsibilities,
                                       ArrayNode mainFlow,
                                       ArrayNode keyMethods,
                                       ArrayNode dependencies,
                                       ArrayNode callChainExplanation,
                                       ArrayNode sseFlowExplanation,
                                       ArrayNode importantDetails,
                                       ArrayNode sourceEvidence) { // 从真实结果填充说明数组。
        JsonNode primary = bundle.primaryResult(); // 主分析结果。
        JsonNode secondary = bundle.secondaryResult(); // 辅助分析结果。
        switch (explanationType) { // 按说明类型分别整理。
            case CLASS -> fillClassExplanation(primary, audience, responsibilities, mainFlow, keyMethods, dependencies, importantDetails, sourceEvidence); // 类说明。
            case METHOD -> fillMethodExplanation(primary, secondary, audience, mainFlow, keyMethods, dependencies, callChainExplanation, importantDetails, sourceEvidence); // 方法说明。
            case CONTROLLER_ENDPOINT -> fillControllerExplanation(primary, audience, responsibilities, mainFlow, keyMethods, dependencies, callChainExplanation, importantDetails, sourceEvidence); // 接口说明。
            case TOOL_CHAIN -> fillToolExplanation(primary, audience, responsibilities, mainFlow, keyMethods, dependencies, callChainExplanation, importantDetails, sourceEvidence); // Tool 说明。
            case CALL_CHAIN -> fillCallChainExplanation(primary, audience, responsibilities, mainFlow, dependencies, callChainExplanation, importantDetails, sourceEvidence); // 调用链说明。
            case SSE_CHAIN -> fillSseExplanation(primary, audience, responsibilities, mainFlow, dependencies, callChainExplanation, sseFlowExplanation, importantDetails, sourceEvidence); // SSE 说明。
            case AUTO -> fillClassExplanation(primary, audience, responsibilities, mainFlow, keyMethods, dependencies, importantDetails, sourceEvidence); // 兜底。
        }
        if (audience == Audience.INTERVIEW) { // 面试话术补充表达角度。
            importantDetails.add("面试表达时可以围绕“职责、入口、依赖、安全边界、可追踪结果”展开，但只描述扫描结果中真实存在的类和调用。"); // 不夸大实现。
        }
        if (detailLevel == DetailLevel.BRIEF) { // 简要模式提醒输出克制。
            importantDetails.add("简要模式只保留核心职责和主链路，详细调用可切换到 DETAILED。"); // 说明裁剪策略。
        }
    }

    private void fillClassExplanation(JsonNode result,
                                      Audience audience,
                                      ArrayNode responsibilities,
                                      ArrayNode mainFlow,
                                      ArrayNode keyMethods,
                                      ArrayNode dependencies,
                                      ArrayNode importantDetails,
                                      ArrayNode sourceEvidence) { // 生成类/文件说明。
        String className = firstNonBlank(result.path("classInfo").path("name").asText(""), result.path("className").asText(""), result.path("fileName").asText("")); // 类名。
        responsibilities.add(formatAudience(audience, "该文件的核心对象是 " + safe(className) + "，主要职责应以扫描到的类信息、字段、方法、接口和 Tool 元信息为准。")); // 职责。
        addMethods(result.path("methods"), keyMethods, sourceEvidence, result.path("path").asText("")); // 方法和方法证据。
        addFields(result.path("fields"), dependencies); // 字段作为依赖或状态。
        addSpringEndpoints(result.path("springEndpoints"), mainFlow, sourceEvidence, result.path("path").asText("")); // Controller 接口摘要。
        addAiToolInfo(result.path("aiToolInfo"), importantDetails, sourceEvidence, result.path("path").asText("")); // AI Tool 信息。
        addClassEvidence(result, sourceEvidence); // 类声明证据。
    }

    private void fillMethodExplanation(JsonNode primary,
                                       JsonNode secondary,
                                       Audience audience,
                                       ArrayNode mainFlow,
                                       ArrayNode keyMethods,
                                       ArrayNode dependencies,
                                       ArrayNode callChainExplanation,
                                       ArrayNode importantDetails,
                                       ArrayNode sourceEvidence) { // 生成方法说明。
        String entryMethod = firstNonBlank(primary.path("entryMethod").asText(""), primary.path("methodName").asText("")); // 入口方法。
        mainFlow.add(formatAudience(audience, "入口方法为 " + safe(entryMethod) + "，说明基于调用链分析中识别到的内部调用、外部依赖调用和候选目标文件。")); // 主流程。
        addDependencies(primary.path("dependencies"), dependencies, sourceEvidence, primary.path("path").asText("")); // 依赖。
        addCalls(primary.path("internalCalls"), "内部方法调用", callChainExplanation, sourceEvidence, primary.path("path").asText("")); // 内部调用。
        addCalls(primary.path("externalCalls"), "外部依赖调用", callChainExplanation, sourceEvidence, primary.path("path").asText("")); // 外部调用。
        addCalls(primary.path("utilityCalls"), "工具/静态方法调用", callChainExplanation, sourceEvidence, primary.path("path").asText("")); // 工具调用。
        addCandidateTargets(primary.path("candidateTargets"), importantDetails); // 候选目标。
        if (secondary != null && secondary.path("success").asBoolean(false)) { // 辅助调用链成功。
            addCalls(secondary.path("internalCalls"), "辅助调用链内部调用", keyMethods, sourceEvidence, secondary.path("path").asText("")); // 补充方法。
        }
    }

    private void fillControllerExplanation(JsonNode result,
                                           Audience audience,
                                           ArrayNode responsibilities,
                                           ArrayNode mainFlow,
                                           ArrayNode keyMethods,
                                           ArrayNode dependencies,
                                           ArrayNode callChainExplanation,
                                           ArrayNode importantDetails,
                                           ArrayNode sourceEvidence) { // 生成 Controller 接口说明。
        String endpoint = firstNonBlank(result.path("endpoint").asText(""), firstEndpointPath(result.path("endpoints"))); // endpoint。
        responsibilities.add(formatAudience(audience, "该接口说明围绕 " + safe(endpoint) + " 的 Controller 方法和它调用的 Service 展开。")); // 职责。
        addEndpoints(result.path("endpoints"), mainFlow, sourceEvidence, firstNonBlank(result.path("matchedControllerPath").asText(""), result.path("path").asText(""))); // 接口。
        addDependencies(result.path("dependencies"), dependencies, sourceEvidence, result.path("matchedControllerPath").asText("")); // 依赖。
        addCalls(result.path("serviceCalls"), "Service 调用", callChainExplanation, sourceEvidence, result.path("matchedControllerPath").asText("")); // Service 调用。
        addCalls(result.path("serviceMethodCalls"), "ServiceImpl 一层调用", keyMethods, sourceEvidence, ""); // 二层调用。
        addCandidateTargets(result.path("candidateEndpoints"), importantDetails); // 未命中候选也可说明。
    }

    private void fillToolExplanation(JsonNode result,
                                     Audience audience,
                                     ArrayNode responsibilities,
                                     ArrayNode mainFlow,
                                     ArrayNode keyMethods,
                                     ArrayNode dependencies,
                                     ArrayNode callChainExplanation,
                                     ArrayNode importantDetails,
                                     ArrayNode sourceEvidence) { // 生成 Tool 链路说明。
        JsonNode aiToolInfo = result.path("aiToolInfo"); // Tool 元信息。
        String toolName = firstNonBlank(aiToolInfo.path("toolName").asText(""), result.path("toolName").asText("")); // toolName。
        responsibilities.add(formatAudience(audience, "该 AI Tool 的 toolName 是 " + safe(toolName) + "，入口通常是 execute，说明基于真实 Tool 文件和依赖调用分类。")); // 职责。
        mainFlow.add("execute 入口会先处理工具参数，再进入内部方法或外部依赖调用；具体步骤以下方 internalCalls 和组件调用为准。"); // 主流程。
        addAiToolInfo(aiToolInfo, importantDetails, sourceEvidence, result.path("path").asText("")); // Tool 信息。
        addDependencies(result.path("dependencies"), dependencies, sourceEvidence, result.path("path").asText("")); // 依赖。
        addCalls(result.path("internalCalls"), "Tool 内部方法调用", keyMethods, sourceEvidence, result.path("path").asText("")); // 内部方法。
        addCalls(result.path("serviceCalls"), "Service 调用", callChainExplanation, sourceEvidence, result.path("path").asText("")); // Service。
        addCalls(result.path("guardCalls"), "Guard 安全组件调用", callChainExplanation, sourceEvidence, result.path("path").asText("")); // Guard。
        addCalls(result.path("registryCalls"), "Registry 注册表调用", callChainExplanation, sourceEvidence, result.path("path").asText("")); // Registry。
        addCalls(result.path("mapperCalls"), "Mapper 调用", callChainExplanation, sourceEvidence, result.path("path").asText("")); // Mapper。
        addCalls(result.path("repositoryCalls"), "Repository 调用", callChainExplanation, sourceEvidence, result.path("path").asText("")); // Repository。
        addCalls(result.path("utilityCalls"), "工具组件调用", callChainExplanation, sourceEvidence, result.path("path").asText("")); // Utility。
        addCalls(result.path("serviceMethodCalls"), "ServiceImpl 一层调用", importantDetails, sourceEvidence, ""); // 二层调用。
    }

    private void fillCallChainExplanation(JsonNode result,
                                          Audience audience,
                                          ArrayNode responsibilities,
                                          ArrayNode mainFlow,
                                          ArrayNode dependencies,
                                          ArrayNode callChainExplanation,
                                          ArrayNode importantDetails,
                                          ArrayNode sourceEvidence) { // 生成普通调用链说明。
        responsibilities.add(formatAudience(audience, "该说明关注入口类/方法的内部调用、外部依赖调用和候选目标文件。")); // 职责。
        mainFlow.add("调用链采用轻量静态扫描，按入口方法内出现的内部方法、字段依赖和工具调用进行分类。"); // 主流程。
        addDependencies(result.path("dependencies"), dependencies, sourceEvidence, result.path("path").asText("")); // 依赖。
        addCalls(result.path("internalCalls"), "内部调用", callChainExplanation, sourceEvidence, result.path("path").asText("")); // 内部。
        addCalls(result.path("externalCalls"), "外部调用", callChainExplanation, sourceEvidence, result.path("path").asText("")); // 外部。
        addCalls(result.path("utilityCalls"), "工具调用", callChainExplanation, sourceEvidence, result.path("path").asText("")); // 工具。
        addCandidateTargets(result.path("candidateTargets"), importantDetails); // 候选。
    }

    private void fillSseExplanation(JsonNode result,
                                    Audience audience,
                                    ArrayNode responsibilities,
                                    ArrayNode mainFlow,
                                    ArrayNode dependencies,
                                    ArrayNode callChainExplanation,
                                    ArrayNode sseFlowExplanation,
                                    ArrayNode importantDetails,
                                    ArrayNode sourceEvidence) { // 生成 SSE 链路说明。
        responsibilities.add(formatAudience(audience, "该说明聚焦 SSE/流式响应从前端发起、后端 Controller、事件发送到前端接收的链路。")); // 职责。
        addSseItems(result.path("frontendCalls"), "前端发起点", sseFlowExplanation, sourceEvidence); // 前端请求。
        addSseItems(result.path("backendSseEndpoints"), "后端 SSE Controller", sseFlowExplanation, sourceEvidence); // 后端接口。
        addCalls(result.path("serviceCalls"), "Controller 到 Service", callChainExplanation, sourceEvidence, ""); // Service。
        addSseItems(result.path("backendEventSenders"), "后端事件发送点", sseFlowExplanation, sourceEvidence); // 发送事件。
        addSseItems(result.path("frontendEventHandlers"), "前端事件接收点", sseFlowExplanation, sourceEvidence); // 前端接收。
        addCalls(result.path("toolCalls"), "后端 Tool 候选调用", dependencies, sourceEvidence, ""); // Tool 候选。
        addCandidateTargets(result.path("candidateEvents"), importantDetails); // 候选事件。
        if (result.path("frontendCalls").size() == 0 && result.path("frontendEventHandlers").size() == 0) { // 前端为空。
            importantDetails.add("本次扫描未找到明确前端 SSE 发起或事件接收点，不能编造前端文件。"); // 如实说明。
        }
    }

    private String buildSummary(CodeExplanationType explanationType, Audience audience, JsonNode result) { // 构造一句话概览。
        String path = firstNonBlank(result.path("path").asText(""), result.path("matchedControllerPath").asText("")); // 路径。
        String className = firstNonBlank(result.path("className").asText(""), result.path("classInfo").path("name").asText(""), result.path("fileName").asText("")); // 类名。
        String target = firstNonBlank(result.path("endpoint").asText(""), result.path("toolName").asText(""),
                result.path("eventName").asText(""), className, path); // 优先显示业务目标。
        String prefix = audience == Audience.INTERVIEW ? "面试表达上，可以把 " : "这段代码说明聚焦 "; // 面向对象措辞。
        return prefix + safe(target) + " 的 " + explanationTypeLabel(explanationType) + "，内容基于项目真实静态扫描结果。"; // 概览。
    }

    private void addMethods(JsonNode methods, ArrayNode keyMethods, ArrayNode sourceEvidence, String path) { // 添加方法说明。
        if (!methods.isArray()) { // 无方法数组。
            return; // 结束。
        }
        for (JsonNode method : methods) { // 遍历方法。
            String name = method.path("name").asText(""); // 方法名。
            if (name.isBlank()) { // 无名跳过。
                continue; // 下一项。
            }
            keyMethods.add("方法 " + safe(name) + "：返回 " + safe(method.path("returnType").asText("-"))
                    + "，参数 " + safe(method.path("parameters").asText("-")) + "。"); // 方法摘要。
            addEvidence(sourceEvidence, path, null, name, method.path("lineNumber").asInt(0), "METHOD_DECLARATION"); // 方法证据。
        }
    }

    private void addFields(JsonNode fields, ArrayNode dependencies) { // 添加字段说明。
        if (!fields.isArray()) { // 无字段数组。
            return; // 结束。
        }
        for (JsonNode field : fields) { // 遍历字段。
            String name = field.path("name").asText(""); // 字段名。
            if (!name.isBlank()) { // 有字段名。
                dependencies.add("字段 " + safe(name) + "：类型 " + safe(field.path("type").asText("-")) + "。"); // 字段说明。
            }
        }
    }

    private void addSpringEndpoints(JsonNode endpoints, ArrayNode mainFlow, ArrayNode sourceEvidence, String path) { // 添加结构分析中的接口摘要。
        if (!endpoints.isArray()) { // 无接口数组。
            return; // 结束。
        }
        for (JsonNode endpoint : endpoints) { // 遍历接口。
            String endpointPath = firstNonBlank(endpoint.path("path").asText(""), endpoint.path("fullPath").asText("")); // 接口路径。
            mainFlow.add("接口 " + safe(endpoint.path("httpMethod").asText("-")) + " " + safe(endpointPath)
                    + " -> Controller 方法 " + safe(endpoint.path("methodName").asText("-")) + "。"); // 接口摘要。
            addEndpointEvidence(sourceEvidence, endpoint, path); // 接口证据。
        }
    }

    private void addEndpoints(JsonNode endpoints, ArrayNode mainFlow, ArrayNode sourceEvidence, String path) { // 添加 Controller 专项接口摘要。
        if (!endpoints.isArray()) { // 无接口数组。
            return; // 结束。
        }
        for (JsonNode endpoint : endpoints) { // 遍历 endpoint。
            String endpointPath = firstNonBlank(endpoint.path("path").asText(""), endpoint.path("endpoint").asText("")); // 路径。
            mainFlow.add("后端入口为 " + safe(endpoint.path("httpMethod").asText("-")) + " " + safe(endpointPath)
                    + "，Controller 方法是 " + safe(firstNonBlank(endpoint.path("controllerMethod").asText(""), endpoint.path("methodName").asText(""))) + "。"); // 流程说明。
            addEndpointEvidence(sourceEvidence, endpoint, path); // 证据。
        }
    }

    private void addAiToolInfo(JsonNode aiToolInfo, ArrayNode importantDetails, ArrayNode sourceEvidence, String path) { // 添加 AI Tool 信息。
        if (aiToolInfo == null || aiToolInfo.isMissingNode() || aiToolInfo.size() == 0) { // 无 Tool 信息。
            return; // 结束。
        }
        if (aiToolInfo.path("isAiTool").asBoolean(false)) { // 是 AI Tool。
            importantDetails.add("AI Tool 识别：toolName=" + safe(aiToolInfo.path("toolName").asText("-"))
                    + "，className=" + safe(aiToolInfo.path("className").asText("-"))
                    + "，hasExecuteMethod=" + aiToolInfo.path("hasExecuteMethod").asBoolean(false) + "。"); // Tool 摘要。
            addEvidence(sourceEvidence, path, aiToolInfo.path("className").asText(""), "execute", 0, "AI_TOOL"); // Tool 证据。
        }
    }

    private void addDependencies(JsonNode deps, ArrayNode dependencies, ArrayNode sourceEvidence, String path) { // 添加依赖对象。
        if (!deps.isArray()) { // 无依赖。
            return; // 结束。
        }
        for (JsonNode dep : deps) { // 遍历依赖。
            String fieldName = dep.path("fieldName").asText(""); // 字段名。
            String type = dep.path("type").asText(""); // 类型名。
            dependencies.add("依赖 " + safe(firstNonBlank(fieldName, dep.path("objectName").asText("")))
                    + "：" + safe(type) + "，分类 " + safe(dep.path("kind").asText(dep.path("injectionType").asText("-"))) + "。"); // 依赖摘要。
            addEvidence(sourceEvidence, path, type, null, dep.path("lineNumber").asInt(0), "DEPENDENCY_DECLARATION"); // 依赖证据。
            addCandidateTargets(dep.path("candidateFiles"), dependencies); // 依赖候选文件。
        }
    }

    private void addCalls(JsonNode calls, String label, ArrayNode target, ArrayNode sourceEvidence, String defaultPath) { // 添加调用说明。
        if (!calls.isArray()) { // 无调用数组。
            return; // 结束。
        }
        for (JsonNode call : calls) { // 遍历调用。
            String methodName = firstNonBlank(call.path("methodName").asText(""), call.path("toMethod").asText(""), call.path("callee").asText("")); // 方法名。
            String objectName = firstNonBlank(call.path("objectName").asText(""), call.path("targetType").asText(""), call.path("serviceType").asText("")); // 对象或类型。
            String fromMethod = call.path("fromMethod").asText(""); // 来源方法。
            target.add(label + "：" + safe(fromMethod) + " -> " + safe(objectName) + "." + safe(methodName)); // 调用摘要。
            addEvidence(sourceEvidence, firstNonBlank(call.path("filePath").asText(""), defaultPath),
                    objectName, methodName, call.path("lineNumber").asInt(0), "CALL_EXPRESSION"); // 调用证据。
            addCandidateTargets(call.path("candidateTargets"), target); // 候选目标文件。
        }
    }

    private void addSseItems(JsonNode items, String label, ArrayNode target, ArrayNode sourceEvidence) { // 添加 SSE 条目。
        if (!items.isArray()) { // 无 SSE 项。
            return; // 结束。
        }
        for (JsonNode item : items) { // 遍历 SSE 项。
            String path = firstNonBlank(item.path("filePath").asText(""), item.path("controllerPath").asText(""), item.path("path").asText("")); // 文件路径。
            String eventName = item.path("eventName").asText(""); // 事件名。
            String endpoint = firstNonBlank(item.path("endpoint").asText(""), item.path("path").asText("")); // endpoint。
            target.add(label + "：" + safe(firstNonBlank(eventName, endpoint, path)) + "，文件 " + safe(path) + "。"); // SSE 摘要。
            addEvidence(sourceEvidence, path, item.path("className").asText(""), item.path("methodName").asText(""),
                    item.path("lineNumber").asInt(0), "SSE_FLOW_POINT"); // SSE 证据。
        }
    }

    private void addCandidateTargets(JsonNode candidates, ArrayNode target) { // 添加候选目标文件或候选项。
        if (candidates == null || !candidates.isArray()) { // 无候选数组。
            return; // 结束。
        }
        for (JsonNode candidate : candidates) { // 遍历候选。
            if (candidate.isTextual()) { // 纯字符串候选。
                target.add("候选目标文件：" + safe(candidate.asText())); // 写入候选。
            } else {
                String path = firstNonBlank(candidate.path("filePath").asText(""), candidate.path("path").asText(""),
                        candidate.path("controllerPath").asText("")); // 候选路径。
                String name = firstNonBlank(candidate.path("eventName").asText(""), candidate.path("endpoint").asText(""),
                        candidate.path("methodName").asText(""), path); // 候选名。
                if (!name.isBlank()) { // 有候选信息。
                    target.add("候选项：" + safe(name) + (path.isBlank() ? "" : "，文件 " + safe(path))); // 写入候选。
                }
            }
        }
    }

    private void addClassEvidence(JsonNode result, ArrayNode sourceEvidence) { // 添加类声明证据。
        JsonNode classInfo = result.path("classInfo"); // 类信息。
        if (classInfo.isObject() && !classInfo.path("name").asText("").isBlank()) { // 有类名。
            addEvidence(sourceEvidence, result.path("path").asText(""), classInfo.path("name").asText(""),
                    null, classInfo.path("lineNumber").asInt(0), "CLASS_DECLARATION"); // 类声明证据。
        }
    }

    private void addEndpointEvidence(ArrayNode sourceEvidence, JsonNode endpoint, String path) { // 添加 endpoint 证据。
        ObjectNode evidence = objectMapper.createObjectNode(); // 创建证据对象。
        putIfText(evidence, "filePath", firstNonBlank(path, endpoint.path("controllerPath").asText(""), endpoint.path("filePath").asText(""))); // 文件路径。
        putIfText(evidence, "endpoint", firstNonBlank(endpoint.path("path").asText(""), endpoint.path("endpoint").asText(""), endpoint.path("fullPath").asText(""))); // endpoint。
        putIfText(evidence, "httpMethod", endpoint.path("httpMethod").asText("")); // HTTP 方法。
        putIfText(evidence, "controllerClass", endpoint.path("controllerClass").asText("")); // Controller 类。
        putIfText(evidence, "controllerMethod", firstNonBlank(endpoint.path("controllerMethod").asText(""), endpoint.path("methodName").asText(""))); // Controller 方法。
        putIfPositive(evidence, "lineNumber", endpoint.path("lineNumber").asInt(0)); // 行号。
        evidence.put("evidenceType", "SPRING_ENDPOINT"); // 证据类型。
        addLimitedEvidence(sourceEvidence, evidence); // 写入证据。
    }

    private void addEvidence(ArrayNode sourceEvidence,
                             String filePath,
                             String className,
                             String methodName,
                             int lineNumber,
                             String evidenceType) { // 添加通用证据。
        ObjectNode evidence = objectMapper.createObjectNode(); // 创建证据。
        putIfText(evidence, "filePath", filePath); // 文件路径。
        putIfText(evidence, "className", className); // 类名。
        putIfText(evidence, "methodName", methodName); // 方法名。
        putIfPositive(evidence, "lineNumber", lineNumber); // 行号。
        evidence.put("evidenceType", evidenceType); // 证据类型。
        addLimitedEvidence(sourceEvidence, evidence); // 写入。
    }

    private void addLimitedEvidence(ArrayNode sourceEvidence, ObjectNode evidence) { // 限制证据数量并去重。
        if (sourceEvidence == null || evidence == null || evidence.size() == 0 || sourceEvidence.size() >= MAX_EVIDENCE_ITEMS) { // 无效或已满。
            return; // 结束。
        }
        String signature = evidence.toString(); // 简单签名。
        for (JsonNode existing : sourceEvidence) { // 遍历已有证据。
            if (existing.toString().equals(signature)) { // 重复。
                return; // 跳过。
            }
        }
        sourceEvidence.add(evidence); // 添加证据。
    }

    private ArrayNode trimArray(ArrayNode source, int limit) { // 裁剪数组。
        ArrayNode target = objectMapper.createArrayNode(); // 新数组。
        if (source == null) { // 空数组。
            return target; // 返回空。
        }
        int max = Math.max(0, Math.min(limit, source.size())); // 计算上限。
        for (int i = 0; i < max; i++) { // 遍历前 N 项。
            target.add(source.get(i)); // 复制。
        }
        return target; // 返回裁剪数组。
    }

    private int detailLimit(DetailLevel detailLevel) { // 根据详细程度限制数组大小。
        return switch (detailLevel) { // 控制说明长度。
            case BRIEF -> 5; // 简要。
            case NORMAL -> MAX_TEXT_ITEMS; // 标准。
            case DETAILED -> 20; // 详细。
        };
    }

    private void copyWarnings(JsonNode source, ArrayNode warnings) { // 复制底层 warnings。
        JsonNode sourceWarnings = source == null ? null : source.path("warnings"); // 读取 warnings。
        if (sourceWarnings != null && sourceWarnings.isArray()) { // 是数组。
            for (JsonNode warning : sourceWarnings) { // 遍历。
                if (warning.isTextual()) { // 只复制文本。
                    warnings.add(warning.asText()); // 写入。
                }
            }
        }
    }

    private void copyTargetFields(ObjectNode target, ObjectNode request, JsonNode result) { // 复制轻量目标字段。
        putIfText(target, "path", firstNonBlank(text(request, "path"), text(result, "path"), text(result, "matchedControllerPath"))); // path。
        putIfText(target, "fileName", firstNonBlank(text(result, "fileName"), fileNameOf(text(target, "path")))); // fileName。
        putIfText(target, "className", firstNonBlank(text(request, "className"), text(result, "className"), result.path("classInfo").path("name").asText(""))); // className。
        putIfText(target, "methodName", firstNonBlank(text(request, "methodName"), text(result, "methodName"), text(result, "entryMethod"))); // methodName。
        putIfText(target, "endpoint", firstNonBlank(text(request, "endpoint"), text(result, "endpoint"))); // endpoint。
        putIfText(target, "toolName", firstNonBlank(text(request, "toolName"), text(result, "toolName"), result.path("aiToolInfo").path("toolName").asText(""))); // toolName。
        putIfText(target, "eventName", firstNonBlank(text(request, "eventName"), text(result, "eventName"))); // eventName。
        putIfText(target, "frontendKeyword", firstNonBlank(text(request, "frontendKeyword"), text(result, "frontendKeyword"))); // frontendKeyword。
    }

    private void copyCandidateFields(ObjectNode failure, JsonNode primary) { // 复制真实候选字段。
        copyArrayIfPresent(failure, primary, "candidates"); // 普通候选。
        copyArrayIfPresent(failure, primary, "candidateEndpoints"); // endpoint 候选。
        copyArrayIfPresent(failure, primary, "candidateEvents"); // SSE 事件候选。
        copyArrayIfPresent(failure, primary, "candidateTools"); // Tool 候选。
    }

    private void copyArrayIfPresent(ObjectNode target, JsonNode source, String fieldName) { // 复制候选数组。
        if (source != null && source.path(fieldName).isArray()) { // 字段是数组。
            target.set(fieldName, source.path(fieldName)); // 复制。
        }
    }

    private String firstEndpointPath(JsonNode endpoints) { // 从 endpoint 数组读取第一个路径。
        if (endpoints != null && endpoints.isArray() && endpoints.size() > 0) { // 有 endpoint。
            JsonNode first = endpoints.get(0); // 第一项。
            return firstNonBlank(first.path("path").asText(""), first.path("endpoint").asText(""), first.path("fullPath").asText("")); // 返回路径。
        }
        return ""; // 无。
    }

    private boolean hasText(ObjectNode node, String fieldName) { // 判断参数字段是否有文本。
        return node != null && node.hasNonNull(fieldName) && !node.path(fieldName).asText("").isBlank(); // 非空白。
    }

    private String firstText(ObjectNode node, String... fields) { // 读取第一个非空字段。
        if (fields == null) { // 空字段列表。
            return ""; // 返回空。
        }
        for (String field : fields) { // 遍历字段。
            String value = node == null ? "" : node.path(field).asText(""); // 读值。
            if (value != null && !value.isBlank()) { // 命中。
                return value; // 返回。
            }
        }
        return ""; // 无。
    }

    private String text(JsonNode node, String fieldName) { // 读取文本字段。
        return node == null ? "" : node.path(fieldName).asText(""); // 兜底空字符串。
    }

    private String firstNonBlank(String... values) { // 返回第一个非空字符串。
        if (values == null) { // 空数组。
            return ""; // 兜底。
        }
        for (String value : values) { // 遍历。
            if (value != null && !value.isBlank()) { // 命中。
                return value.trim(); // 返回去空格值。
            }
        }
        return ""; // 无值。
    }

    private boolean looksLikeToolTarget(String pathOrClass) { // 判断目标是否像 AI Tool。
        if (pathOrClass == null || pathOrClass.isBlank()) { // 空值。
            return false; // 不是。
        }
        String lower = pathOrClass.replace('\\', '/').toLowerCase(Locale.ROOT); // 统一路径。
        return lower.endsWith("tool.java") || lower.endsWith("tool") || lower.contains("/tool/"); // Tool 文件/类/目录。
    }

    private String formatAudience(Audience audience, String text) { // 根据 audience 做轻量措辞。
        if (audience == Audience.INTERVIEW) { // 面试场景。
            return "面试可表述为：" + text; // 加话术前缀。
        }
        if (audience == Audience.BEGINNER) { // 新手场景。
            return "通俗来说：" + text; // 加通俗前缀。
        }
        return text; // 开发者说明保持工程表达。
    }

    private String explanationTypeLabel(CodeExplanationType type) { // 说明类型中文标签。
        return switch (type) { // 转为中文。
            case CLASS -> "类/文件职责说明"; // 类说明。
            case METHOD -> "方法执行流程说明"; // 方法说明。
            case CONTROLLER_ENDPOINT -> "Controller 接口流程说明"; // 接口。
            case TOOL_CHAIN -> "AI Tool 执行链路说明"; // Tool。
            case CALL_CHAIN -> "普通调用链说明"; // 调用链。
            case SSE_CHAIN -> "SSE 事件链路说明"; // SSE。
            case AUTO -> "代码说明"; // 兜底。
        };
    }

    private String safe(String value) { // 安全展示短文本。
        if (value == null || value.isBlank()) { // 空值。
            return "-"; // 占位。
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim(); // 移除换行。
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "..."; // 控制长度。
    }

    private String fileNameOf(String path) { // 从相对路径提取文件名。
        if (path == null || path.isBlank()) { // 空路径。
            return ""; // 返回空。
        }
        String normalized = path.replace('\\', '/'); // 统一路径分隔符。
        int index = normalized.lastIndexOf('/'); // 找最后一个分隔符。
        return index >= 0 ? normalized.substring(index + 1) : normalized; // 返回文件名。
    }

    private void putIfText(ObjectNode node, String fieldName, String value) { // 非空文本写入 JSON。
        if (node != null && value != null && !value.isBlank()) { // 有效值。
            node.put(fieldName, value); // 写入。
        }
    }

    private void putIfPositive(ObjectNode node, String fieldName, int value) { // 正整数写入 JSON。
        if (node != null && value > 0) { // 有效行号。
            node.put(fieldName, value); // 写入。
        }
    }

    private enum DetailLevel { // 说明详细程度。
        BRIEF, // 简要。
        NORMAL, // 标准。
        DETAILED; // 详细。

        private static DetailLevel from(String rawValue) { // 解析 detailLevel。
            if (rawValue == null || rawValue.isBlank()) { // 未传。
                return NORMAL; // 默认标准。
            }
            try {
                return DetailLevel.valueOf(rawValue.trim().replace('-', '_').toUpperCase(Locale.ROOT)); // 解析枚举。
            } catch (IllegalArgumentException ex) {
                return NORMAL; // 非法值兜底。
            }
        }
    }

    private enum Audience { // 说明面向对象。
        DEVELOPER, // 开发者。
        INTERVIEW, // 面试话术。
        BEGINNER; // 新手。

        private static Audience from(String rawValue) { // 解析 audience。
            if (rawValue == null || rawValue.isBlank()) { // 未传。
                return DEVELOPER; // 默认开发者。
            }
            try {
                return Audience.valueOf(rawValue.trim().replace('-', '_').toUpperCase(Locale.ROOT)); // 解析枚举。
            } catch (IllegalArgumentException ex) {
                return DEVELOPER; // 非法值兜底。
            }
        }
    }

    private static class AnalysisBundle { // 底层分析结果组合。
        private final JsonNode primaryResult; // 主分析结果。
        private final JsonNode secondaryResult; // 辅助分析结果。

        private AnalysisBundle(JsonNode primaryResult, JsonNode secondaryResult) { // 构造组合。
            this.primaryResult = primaryResult; // 保存主结果。
            this.secondaryResult = secondaryResult; // 保存辅助结果。
        }

        private JsonNode primaryResult() { // 读取主结果。
            return primaryResult; // 返回主结果。
        }

        private JsonNode secondaryResult() { // 读取辅助结果。
            return secondaryResult; // 返回辅助结果。
        }

        private boolean primarySuccess() { // 判断主分析是否成功。
            return primaryResult != null && primaryResult.path("success").asBoolean(false); // success=true。
        }
    }
}

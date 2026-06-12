package com.agent.analysis.code; // 项目代码内部分析器包。

import com.agent.toolcalling.project.analysis.CodeAnalysisType; // analyzeCode 统一分析类型。
import com.fasterxml.jackson.databind.JsonNode; // 读取底层 Analyzer 返回的真实分析结果。
import com.fasterxml.jackson.databind.node.ArrayNode; // 构造 risks/safePoints/evidence 数组。
import com.fasterxml.jackson.databind.node.ObjectNode; // 构造 code_risk_analysis JSON。
import org.springframework.stereotype.Component; // 注册为内部 Spring Bean，供 AnalyzeCodeTool 分发使用。

import java.util.LinkedHashSet; // 去重收集真实事件名等。
import java.util.Locale; // 解析 riskScope/riskLevel/riskCategories。
import java.util.Set; // 风险类型过滤集合。

/**
 * P5.7 代码风险点内部分析器。
 *
 * <p>适用场景：用户要求“分析 xxx 的风险点 / 有没有空指针 / 接口有没有参数校验 / Tool 有没有路径穿越 /
 * SSE 链路有哪些风险”时，对外仍然只调用 analyzeCode，AnalyzeCodeTool 根据 analysisType=RISK 分发到本组件。
 * 本组件不会注册为 AI Tool，而是复用已有 CodeStructureAnalyzer、CallChainAnalyzer、
 * ControllerServiceChainAnalyzer、ToolServiceChainAnalyzer、SseEventChainAnalyzer 的真实扫描结果，
 * 把其中的依赖、调用、接口、Guard、Mapper、事件发送等证据转换成面向开发者的风险点说明。</p>
 *
 * <p>调用链：ToolCallingChatServiceImpl 构造 analyzeCode arguments（analysisType=RISK、riskScope、riskLevel、riskCategories）
 * -> AnalyzeCodeTool 规范化 recentProjectTarget/projectFileFocus 和目标参数 -> CodeRiskAnalyzer.execute(arguments)
 * -> 根据 riskScope 调用对应内部 Analyzer 获取真实结果 -> 基于真实证据生成 risks/safePoints
 * -> 按 riskLevel/riskCategories 过滤 -> 返回 code_risk_analysis 作为 code_analysis.result。</p>
 *
 * <p>边界说明：本组件只基于真实扫描结果生成风险说明和简短建议，不输出完整源码，不生成测试步骤、修改方案或 patch，
 * 不修改项目代码、不执行 SQL；静态无法完全确认的风险一律标记 confidence=MEDIUM/LOW，并在描述中写明需要人工核查，
 * 绝不为了输出风险而编造不存在的路径穿越、数据库写入、SSE 或 Service 调用。</p>
 */
@Component // 仅作为 analyzeCode 内部 Handler 注入，不会被 ToolRegistry 暴露为对外 Tool。
public class CodeRiskAnalyzer extends AbstractCodeAnalysisHandler { // P5.7 代码风险点内部分析器。
    private static final String RESULT_TYPE = "code_risk_analysis"; // 内层结果类型。
    private static final int MAX_EVIDENCE_PER_RISK = 6; // 单条风险最多附带的证据数量。
    private static final int DEFAULT_MAX_ITEMS = 100; // 默认最多返回风险点数量。

    // 风险级别。
    private static final String LEVEL_HIGH = "HIGH"; // 高风险。
    private static final String LEVEL_MEDIUM = "MEDIUM"; // 中风险。
    private static final String LEVEL_LOW = "LOW"; // 低风险。
    private static final String LEVEL_INFO = "INFO"; // 提示级。
    // 置信度。
    private static final String CONF_HIGH = "HIGH"; // 高置信。
    private static final String CONF_MEDIUM = "MEDIUM"; // 中置信。
    private static final String CONF_LOW = "LOW"; // 低置信。
    // 已知文件读取/搜索类 Tool 名（用于路径穿越风险判断）。
    private static final Set<String> FILE_ACCESS_TOOL_NAMES = Set.of(
            "readProjectFile", "searchCode", "listProjectTree", "analyzeCode"); // 真实涉及 workspace 文件读取/扫描的 Tool。

    private final CodeStructureAnalyzer codeStructureAnalyzer; // 复用单文件结构分析能力。
    private final CallChainAnalyzer callChainAnalyzer; // 复用普通调用链分析能力。
    private final ControllerServiceChainAnalyzer controllerServiceChainAnalyzer; // 复用 Controller→Service 分析能力。
    private final ToolServiceChainAnalyzer toolServiceChainAnalyzer; // 复用 Tool→业务组件分析能力。
    private final SseEventChainAnalyzer sseEventChainAnalyzer; // 复用 SSE 事件链路分析能力。

    public CodeRiskAnalyzer(CodeStructureAnalyzer codeStructureAnalyzer,
                            CallChainAnalyzer callChainAnalyzer,
                            ControllerServiceChainAnalyzer controllerServiceChainAnalyzer,
                            ToolServiceChainAnalyzer toolServiceChainAnalyzer,
                            SseEventChainAnalyzer sseEventChainAnalyzer) { // 注入已有内部 Analyzer，复用真实扫描结果，不重写解析。
        this.codeStructureAnalyzer = codeStructureAnalyzer; // 保存结构分析器。
        this.callChainAnalyzer = callChainAnalyzer; // 保存调用链分析器。
        this.controllerServiceChainAnalyzer = controllerServiceChainAnalyzer; // 保存 Controller 专项分析器。
        this.toolServiceChainAnalyzer = toolServiceChainAnalyzer; // 保存 Tool 专项分析器。
        this.sseEventChainAnalyzer = sseEventChainAnalyzer; // 保存 SSE 专项分析器。
    }

    @Override
    public CodeAnalysisType analysisType() { // 返回 AnalyzeCodeTool 分发键。
        return CodeAnalysisType.RISK; // P5.7 风险点说明。
    }

    @Override
    public String execute(JsonNode arguments) { // 风险点生成入口。
        ObjectNode request = copyArguments(arguments); // 复制入参，避免影响外层 tool_call_log。
        CodeRiskScope riskScope = resolveRiskScope(request); // 解析或自动判断风险范围。
        String riskLevel = normalizeUpper(getOptionalText(request, "riskLevel", "ALL")); // 风险级别过滤，默认 ALL。
        Set<String> riskCategories = parseRiskCategories(request); // 风险类型过滤集合（空表示不过滤）。
        boolean includeEvidence = resolveBoolean(request, "includeEvidence", true); // 是否返回证据，默认 true。
        boolean includeSuggestion = resolveBoolean(request, "includeSuggestion", true); // 是否返回简短建议，默认 true。
        boolean includeSnippet = resolveBoolean(request, "includeSnippet", false); // 是否返回代码片段，默认 false。
        int maxItems = resolveMaxItems(request); // 风险点上限。
        prepareAnalyzerArguments(request, riskScope, includeSnippet); // 设置底层 Analyzer 入参（analysisType/path/maxDepth 等）。

        JsonNode primary = runAnalysis(riskScope, request); // 调用对应内部 Analyzer 获取真实分析结果。
        if (!primary.path("success").asBoolean(false)) { // 底层真实扫描没有命中目标。
            return buildFailureResult(riskScope, request, primary).toString(); // 不捏造风险，返回失败和真实候选。
        }

        RiskState state = new RiskState(riskLevel, riskCategories, includeEvidence, includeSuggestion, includeSnippet, maxItems); // 汇总过滤条件和结果收集器。
        deriveRisks(riskScope, primary, state); // 基于真实证据生成 risks/safePoints。

        ObjectNode result = objectMapper.createObjectNode(); // 构造 code_risk_analysis。
        result.put("type", RESULT_TYPE); // 写入内层 type。
        result.put("success", true); // 标记成功。
        result.put("riskScope", riskScope.name()); // 写入最终风险范围。
        result.put("riskLevel", riskLevel); // 写入风险级别过滤条件。
        copyTargetFields(result, request, primary); // 复制 path/className/endpoint/toolName/eventName 等轻量目标字段。
        result.put("riskCount", state.risks.size()); // 风险点数量。
        result.set("risks", reindexRisks(state.risks)); // 写入风险点（重排 id）。
        result.set("safePoints", state.safePoints); // 写入已有保护点。
        ArrayNode warnings = objectMapper.createArrayNode(); // 风险说明边界。
        warnings.add("风险点基于项目源码静态扫描结果生成，标记 MEDIUM/LOW 的风险需要人工进一步确认；本说明不输出完整源码，不包含修改方案、测试步骤或 patch。"); // 统一边界说明。
        copyWarnings(primary, warnings); // 透传底层 Analyzer 的不确定项。
        result.set("warnings", warnings); // 写入 warnings。
        return result.toString(); // 返回内层 JSON 字符串。
    }

    // ===================== riskScope 解析与底层分析复用 =====================

    private CodeRiskScope resolveRiskScope(ObjectNode request) { // 解析 riskScope。
        CodeRiskScope requested = CodeRiskScope.from(getOptionalText(request, "riskScope", "AUTO")); // 读取显式 riskScope。
        if (requested != CodeRiskScope.AUTO) { // 显式范围优先。
            return requested; // 返回显式范围。
        }
        if (hasText(request, "eventName") || hasText(request, "frontendKeyword")) { // SSE 事件/前端关键词最明确。
            return CodeRiskScope.SSE_CHAIN; // SSE 链路风险。
        }
        if (hasText(request, "endpoint")) { // 有接口路径默认 Controller 接口风险。
            return CodeRiskScope.CONTROLLER_ENDPOINT; // Controller 接口风险。
        }
        if (hasText(request, "toolName") || looksLikeToolTarget(firstText(request, "path", "className"))) { // Tool 名或 Tool 文件。
            return CodeRiskScope.TOOL_CHAIN; // Tool 执行链路风险。
        }
        if (hasText(request, "methodName")) { // 只有方法名时默认方法风险。
            return CodeRiskScope.METHOD; // 方法风险。
        }
        return CodeRiskScope.CLASS; // 兜底为类/文件风险。
    }

    private void prepareAnalyzerArguments(ObjectNode request, CodeRiskScope riskScope, boolean includeSnippet) { // 设置底层 Analyzer 入参默认值。
        request.put("analysisType", mappedAnalysisType(riskScope).name()); // 底层 Analyzer 只看到它负责的分析类型。
        request.put("includeSnippet", includeSnippet); // 风险默认不贴片段，由 includeSnippet 控制。
        if (!request.hasNonNull("maxItems")) { // 缺少上限时使用 100。
            request.put("maxItems", DEFAULT_MAX_ITEMS); // 保持与其它分析一致。
        }
        // TOOL_CHAIN / CONTROLLER_ENDPOINT / SSE_CHAIN 需要展开一层，才能看到 Service/Mapper/事件发送等风险证据。
        int depth = (riskScope == CodeRiskScope.TOOL_CHAIN
                || riskScope == CodeRiskScope.CONTROLLER_ENDPOINT
                || riskScope == CodeRiskScope.SSE_CHAIN) ? 2 : 1; // 深度选择。
        request.put("maxDepth", depth); // 写入分析深度。
        if (!hasText(request, "path") && hasText(request, "className")
                && (riskScope == CodeRiskScope.CLASS
                || riskScope == CodeRiskScope.METHOD
                || riskScope == CodeRiskScope.CALL_CHAIN
                || riskScope == CodeRiskScope.CONTROLLER_ENDPOINT
                || riskScope == CodeRiskScope.TOOL_CHAIN)) { // 旧 Analyzer 通过 path 字段支持类名定位。
            request.put("path", request.path("className").asText()); // 将 className 作为定位目标传入。
        }
    }

    private JsonNode runAnalysis(CodeRiskScope riskScope, ObjectNode request) { // 执行底层真实分析。
        try {
            return readJson(analyzerFor(riskScope).analyze(request)); // 调用当前风险范围对应 Analyzer。
        } catch (Exception e) {
            ObjectNode failure = objectMapper.createObjectNode(); // 构造失败结果。
            failure.put("type", "analysis_failure"); // 标记内部失败。
            failure.put("success", false); // 执行失败。
            failure.put("message", "未找到真实匹配目标，无法生成风险点说明。"); // 不暴露内部异常。
            return failure; // 返回失败。
        }
    }

    private CodeAnalysisHandler analyzerFor(CodeRiskScope riskScope) { // 风险范围映射到底层 Analyzer。
        return switch (riskScope) { // 复用已有 P5 分析能力。
            case CLASS -> codeStructureAnalyzer; // 类/文件风险基于结构分析。
            case METHOD, CALL_CHAIN -> callChainAnalyzer; // 方法和普通调用链风险基于调用链。
            case CONTROLLER_ENDPOINT -> controllerServiceChainAnalyzer; // 接口风险基于 Controller→Service。
            case TOOL_CHAIN -> toolServiceChainAnalyzer; // Tool 风险基于 Tool→Service。
            case SSE_CHAIN -> sseEventChainAnalyzer; // SSE 风险基于 SSE 链路。
            case AUTO -> codeStructureAnalyzer; // 理论不会进入，兜底结构分析。
        };
    }

    private CodeAnalysisType mappedAnalysisType(CodeRiskScope riskScope) { // 风险范围映射到底层分析类型。
        return switch (riskScope) { // 与 analyzerFor 保持一致。
            case CLASS -> CodeAnalysisType.STRUCTURE; // 结构。
            case METHOD, CALL_CHAIN -> CodeAnalysisType.CALL_CHAIN; // 调用链。
            case CONTROLLER_ENDPOINT -> CodeAnalysisType.CONTROLLER_SERVICE; // Controller→Service。
            case TOOL_CHAIN -> CodeAnalysisType.TOOL_SERVICE; // Tool→Service。
            case SSE_CHAIN -> CodeAnalysisType.SSE_EVENT_CHAIN; // SSE 链路。
            case AUTO -> CodeAnalysisType.STRUCTURE; // 兜底。
        };
    }

    // ===================== 风险点生成（基于真实证据） =====================

    private void deriveRisks(CodeRiskScope riskScope, JsonNode result, RiskState state) { // 按风险范围从真实结果生成风险点。
        switch (riskScope) { // 分范围处理。
            case TOOL_CHAIN -> deriveToolRisks(result, state); // Tool 风险。
            case CONTROLLER_ENDPOINT -> deriveControllerRisks(result, state); // 接口风险。
            case SSE_CHAIN -> deriveSseRisks(result, state); // SSE 风险。
            case METHOD, CALL_CHAIN -> deriveMethodRisks(result, state); // 方法/调用链风险。
            case CLASS, AUTO -> deriveClassRisks(result, state); // 类/文件风险。
        }
    }

    private void deriveToolRisks(JsonNode result, RiskState state) { // 生成 AI Tool 执行链路风险点。
        JsonNode aiToolInfo = result.path("aiToolInfo"); // Tool 元信息。
        String toolName = firstNonBlank(aiToolInfo.path("toolName").asText(""), result.path("toolName").asText("")); // toolName。
        String path = result.path("path").asText(""); // Tool 文件相对路径。
        String className = firstNonBlank(aiToolInfo.path("className").asText(""), result.path("className").asText("")); // Tool 类名。
        boolean fileAccessTool = FILE_ACCESS_TOOL_NAMES.contains(toolName) || lower(path).contains("/tool/project/"); // 是否文件读取/扫描类 Tool。
        boolean hasGuard = hasDependencyType(result.path("dependencies"), "ProjectPathGuard") || result.path("guardCalls").size() > 0; // 是否使用 ProjectPathGuard。

        if (fileAccessTool && hasGuard) { // 文件读取类 Tool 已经过 ProjectPathGuard。
            ArrayNode evidence = state.includeEvidence ? evidenceFromCalls(result.path("guardCalls"), path, "PATH_GUARD_CALL") : objectMapper.createArrayNode(); // Guard 调用证据。
            addGuardDependencyEvidence(result.path("dependencies"), path, evidence, state); // 补充 ProjectPathGuard 依赖证据。
            addSafePoint(state, "路径读取已经过 ProjectPathGuard 校验",
                    buildGuardSafeDescription(collectMethodNames(result.path("guardCalls"))), evidence); // 根据真实 Guard 方法生成保护点说明。
        } else if (fileAccessTool) { // 文件读取类 Tool 未扫描到 ProjectPathGuard。
            addRisk(state, "路径参数缺少可见的 ProjectPathGuard 校验", LEVEL_HIGH, "PATH_TRAVERSAL",
                    "该 Tool 涉及 workspace 文件读取/扫描，但本次静态扫描未发现 ProjectPathGuard 依赖或调用。静态分析无法完全确认，需要人工检查 execute 是否对 path 做绝对路径、路径穿越和敏感文件校验。",
                    evidenceFromAiTool(aiToolInfo, path, className, state), CONF_MEDIUM); // 路径穿越风险（静态推测）。
        }

        if (result.path("mapperCalls").size() > 0 || result.path("repositoryCalls").size() > 0) { // 涉及 Mapper/Repository 写读。
            ArrayNode evidence = mergeEvidence(evidenceFromCalls(result.path("mapperCalls"), path, "MAPPER_CALL"),
                    evidenceFromCalls(result.path("repositoryCalls"), path, "REPOSITORY_CALL"), state); // 证据。
            addRisk(state, "涉及 Mapper/Repository 调用，需确认参数校验与归属", LEVEL_MEDIUM, "DATABASE_WRITE",
                    "该 Tool 链路中扫描到 Mapper/Repository 调用。静态分析无法确认是否有参数校验、事务、用户归属校验或空内容写入，需要人工检查对应 ServiceImpl/Mapper。",
                    evidence, CONF_MEDIUM); // 数据库写入风险（静态推测）。
        }

        if (result.path("registryCalls").size() > 0) { // 通过 ToolRegistry 获取/调用工具。
            addRisk(state, "通过 ToolRegistry 调用其它工具，需确认 toolName 白名单可控", LEVEL_INFO, "TOOL_CALLING",
                    "该链路中扫描到 ToolRegistry 调用。建议确认 toolName 来源可控、有注册白名单，工具调用失败有错误返回；静态分析无法确认是否存在 DSML/tool_calls 泄漏，需要人工核查。",
                    state.includeEvidence ? evidenceFromCalls(result.path("registryCalls"), path, "REGISTRY_CALL") : objectMapper.createArrayNode(), CONF_MEDIUM); // Tool Calling 提示。
        }

        if (result.path("unresolvedCalls").size() >= 10) { // 未解析调用较多，结构偏复杂。
            addRisk(state, "execute 内部调用较多，关注可维护性", LEVEL_LOW, "MAINTAINABILITY",
                    "该 Tool 内部存在较多未解析/外部调用，方法职责可能偏重。静态分析仅提示可维护性，需要人工判断是否拆分。",
                    objectMapper.createArrayNode(), CONF_LOW); // 可维护性。
        }
    }

    private void deriveControllerRisks(JsonNode result, RiskState state) { // 生成 Controller 接口风险点。
        JsonNode endpoints = result.path("endpoints"); // 接口列表。
        String controllerPath = firstNonBlank(result.path("matchedControllerPath").asText(""), result.path("path").asText("")); // Controller 路径。
        if (endpoints.isArray() && endpoints.size() > 0) { // 有真实接口。
            addRisk(state, "接口直接接收前端参数，需确认参数与权限校验", LEVEL_MEDIUM, "PARAM_VALIDATION",
                    "扫描到该 Controller 的接口入口。静态分析无法确认 @RequestBody/@PathVariable 是否做了 null 校验、用户归属和权限校验，是否直接信任前端参数，需要人工检查 Controller 方法体。",
                    state.includeEvidence ? evidenceFromEndpoints(endpoints, controllerPath) : objectMapper.createArrayNode(), CONF_MEDIUM); // 参数校验风险。
        }
        if (result.path("serviceCalls").size() > 0) { // 接口委托给 Service。
            addSafePoint(state, "接口逻辑委托给 Service，职责分层清晰",
                    "Controller 方法把核心逻辑委托给 Service，业务一致性、异常处理和事务以 Service 实现为准，建议进一步分析对应 ServiceImpl 风险（riskScope=TOOL_CHAIN 或 CALL_CHAIN）。",
                    state.includeEvidence ? evidenceFromCalls(result.path("serviceCalls"), controllerPath, "SERVICE_CALL") : objectMapper.createArrayNode()); // 安全点。
        } else if (endpoints.isArray() && endpoints.size() > 0) { // 接口未扫描到 Service 调用。
            addRisk(state, "接口方法未扫描到明确 Service 调用，需确认异常与返回处理", LEVEL_INFO, "EXCEPTION_HANDLING",
                    "本次未扫描到该接口对 Service 的调用。静态分析无法确认接口是否有统一 Result 返回和异常处理，需要人工检查。",
                    objectMapper.createArrayNode(), CONF_LOW); // 异常处理提示。
        }
    }

    private void deriveSseRisks(JsonNode result, RiskState state) { // 生成 SSE 链路风险点。
        Set<String> senderEvents = collectEventNames(result.path("backendEventSenders")); // 后端真实发送的事件名集合。
        boolean hasBackend = result.path("backendSseEndpoints").size() > 0 || !senderEvents.isEmpty(); // 是否扫描到后端 SSE。
        if (!hasBackend) { // 没有后端 SSE 证据时不编造 SSE 风险。
            addRisk(state, "未扫描到后端 SSE 发送点，无法确认流式链路风险", LEVEL_INFO, "SSE_STREAM",
                    "本次未扫描到后端 SSE Controller 或事件发送点。静态分析无法确认流式链路风险，需要人工确认 SSE 实现位置。",
                    objectMapper.createArrayNode(), CONF_LOW); // 信息级提示。
            return; // 结束。
        }
        ArrayNode senderEvidence = state.includeEvidence ? evidenceFromSenders(result.path("backendEventSenders")) : objectMapper.createArrayNode(); // 事件发送证据。
        if (senderEvents.contains("error")) { // 已有 error 事件。
            addSafePoint(state, "后端 SSE 已有 error 事件，异常时会通知前端",
                    "扫描到后端发送 error 事件，后端异常时会通过 SSE 通知前端。", senderEvidence); // 安全点。
        } else { // 未扫描到 error 事件。
            addRisk(state, "未扫描到 error 事件，后端异常可能无法通知前端", LEVEL_MEDIUM, "SSE_STREAM",
                    "扫描到后端 SSE 发送点，但未发现 error 事件发送。静态分析无法确认异常分支是否通知前端并关闭流，需要人工检查 SSE 异常处理。",
                    senderEvidence, CONF_MEDIUM); // SSE 错误处理风险。
        }
        if (senderEvents.contains("done")) { // 已有 done 事件。
            addSafePoint(state, "后端 SSE 已有 done 完成事件",
                    "扫描到后端发送 done 事件，正常流程会通知前端流式结束。", senderEvidence); // 安全点。
        } else { // 未扫描到 done 事件。
            addRisk(state, "未扫描到 done 完成事件，流是否正常关闭需确认", LEVEL_MEDIUM, "SSE_STREAM",
                    "扫描到后端 SSE 发送点，但未发现 done 完成事件。静态分析无法确认流是否在所有分支正常 complete，可能存在资源泄漏，需要人工检查。",
                    senderEvidence, CONF_MEDIUM); // SSE 完成事件风险。
        }
        if (result.path("frontendEventHandlers").size() == 0) { // 未扫描到前端接收点。
            addRisk(state, "未扫描到前端事件接收点，前后端事件契约需人工确认", LEVEL_INFO, "FRONTEND_BACKEND_CONTRACT",
                    "本次未扫描到前端 SSE 事件接收点（项目可能不含前端模块或前端代码不在 workspace 内）。无法确认前端是否处理 error/done 及后端发送的事件名，需要人工核对前后端事件契约。",
                    objectMapper.createArrayNode(), CONF_LOW); // 前后端契约提示。
        }
        if (senderEvents.contains("message")) { // 存在逐 token 流式输出。
            addRisk(state, "存在逐 token 流式输出，关注高并发性能", LEVEL_INFO, "PERFORMANCE",
                    "扫描到 message 事件逐 token 发送，属于常规流式实现；大量并发或超长输出时建议关注连接数和性能，静态分析无法量化。",
                    objectMapper.createArrayNode(), CONF_LOW); // 性能提示。
        }
    }

    private void deriveMethodRisks(JsonNode result, RiskState state) { // 生成方法/普通调用链风险点。
        String path = result.path("path").asText(""); // 文件路径。
        String entryMethod = firstNonBlank(result.path("entryMethod").asText(""), result.path("methodName").asText("")); // 入口方法。
        boolean hasObjectCalls = result.path("externalCalls").size() > 0 || result.path("unresolvedCalls").size() > 0; // 是否存在对象方法调用。
        if (hasObjectCalls) { // 有对象方法调用才提空指针风险。
            ArrayNode evidence = mergeEvidence(evidenceFromCalls(result.path("externalCalls"), path, "EXTERNAL_CALL"),
                    evidenceFromCalls(result.path("unresolvedCalls"), path, "UNRESOLVED_CALL"), state); // 调用证据。
            addRisk(state, "方法内对对象/依赖有方法调用，需确认空指针保护", LEVEL_LOW, "NULL_POINTER",
                    "入口方法 " + safe(entryMethod) + " 内对依赖对象或局部对象存在方法调用。静态分析无法确认调用前是否做 null 判断，需要人工检查可能为空的对象。",
                    evidence, CONF_LOW); // 空指针风险（静态推测）。
        }
        if (hasUtilityReceiver(result.path("utilityCalls"), "log")) { // 已有日志调用。
            addSafePoint(state, "方法内已有日志输出",
                    "扫描到方法内存在日志调用，便于问题追踪。建议确认关键异常分支也有日志。",
                    state.includeEvidence ? evidenceFromCalls(result.path("utilityCalls"), path, "UTILITY_CALL") : objectMapper.createArrayNode()); // 安全点。
        } else { // 未扫描到日志。
            addRisk(state, "方法内未扫描到日志输出，关注可追踪性", LEVEL_LOW, "LOGGING",
                    "本次未扫描到该方法内的日志调用。静态分析无法确认关键路径是否有日志，需要人工判断是否补充。",
                    objectMapper.createArrayNode(), CONF_LOW); // 日志提示。
        }
        if (result.path("internalCalls").size() >= 12) { // 内部调用较多。
            addRisk(state, "方法内部调用较多，关注方法长度与职责", LEVEL_LOW, "MAINTAINABILITY",
                    "入口方法内部调用较多，方法可能偏长。静态分析仅提示可维护性，需要人工判断是否拆分。",
                    objectMapper.createArrayNode(), CONF_LOW); // 可维护性。
        }
    }

    private void deriveClassRisks(JsonNode result, RiskState state) { // 生成类/文件风险点。
        String path = result.path("path").asText(""); // 文件路径。
        JsonNode methods = result.path("methods"); // 方法列表。
        JsonNode fields = result.path("fields"); // 字段列表。
        JsonNode aiToolInfo = result.path("aiToolInfo"); // Tool 信息。
        if (methods.isArray() && methods.size() >= 20) { // 类方法较多。
            addRisk(state, "类方法数量较多，关注单一职责", LEVEL_LOW, "MAINTAINABILITY",
                    "扫描到该类方法数量较多（" + methods.size() + " 个）。静态分析仅提示类可能偏大、职责偏多，需要人工判断是否拆分。",
                    classEvidence(result, path, state), CONF_LOW); // 可维护性风险。
        }
        if (fields.isArray() && fields.size() >= 15) { // 字段较多。
            addRisk(state, "类字段数量较多，关注状态复杂度", LEVEL_LOW, "MAINTAINABILITY",
                    "扫描到该类字段数量较多（" + fields.size() + " 个）。静态分析仅提示状态较复杂，需要人工判断。",
                    objectMapper.createArrayNode(), CONF_LOW); // 可维护性风险。
        }
        if (aiToolInfo.path("isAiTool").asBoolean(false)) { // 该文件是 AI Tool。
            addRisk(state, "该文件是 AI Tool，建议用 TOOL_CHAIN 范围分析执行链路风险", LEVEL_INFO, "TOOL_CALLING",
                    "结构分析识别到该文件是 AI Tool（toolName=" + safe(aiToolInfo.path("toolName").asText("-"))
                            + "）。如需分析路径穿越、依赖组件和 Service 调用风险，建议使用 riskScope=TOOL_CHAIN。",
                    state.includeEvidence ? evidenceFromAiTool(aiToolInfo, path, aiToolInfo.path("className").asText(""), state) : objectMapper.createArrayNode(), CONF_MEDIUM); // Tool 信息提示。
        }
        JsonNode springEndpoints = result.path("springEndpoints"); // Controller 接口。
        if (springEndpoints.isArray() && springEndpoints.size() > 0) { // 是 Controller。
            addRisk(state, "该文件包含 Controller 接口，建议用 CONTROLLER_ENDPOINT 范围分析接口风险", LEVEL_INFO, "PARAM_VALIDATION",
                    "结构分析识别到该文件包含 Spring 接口。如需分析参数校验、权限和 Service 链路风险，建议使用 riskScope=CONTROLLER_ENDPOINT。",
                    objectMapper.createArrayNode(), CONF_MEDIUM); // 接口提示。
        }
        if (state.risks.isEmpty() && state.safePoints.isEmpty()) { // 没有明显风险。
            addSafePoint(state, "未扫描到明显风险点",
                    "结构分析未发现类过大、AI Tool 或 Controller 接口等明显风险点。静态分析覆盖有限，仍建议结合方法/调用链范围进一步分析。",
                    classEvidence(result, path, state)); // 安全点，避免只挑毛病。
        }
    }

    // ===================== 证据构造 =====================

    private ArrayNode evidenceFromCalls(JsonNode calls, String defaultPath, String evidenceType) { // 从调用数组构造证据。
        ArrayNode evidence = objectMapper.createArrayNode(); // 证据数组。
        if (!calls.isArray()) { // 非数组。
            return evidence; // 返回空。
        }
        for (JsonNode call : calls) { // 遍历调用。
            if (evidence.size() >= MAX_EVIDENCE_PER_RISK) { // 达到上限。
                break; // 停止。
            }
            String filePath = firstNonBlank(call.path("filePath").asText(""), call.path("fromFile").asText(""), defaultPath); // 文件路径。
            String objectName = firstNonBlank(call.path("objectName").asText(""), call.path("serviceObject").asText(""), call.path("targetType").asText(""), call.path("serviceType").asText("")); // 对象/类型。
            String methodName = firstNonBlank(call.path("methodName").asText(""), call.path("serviceMethod").asText(""), call.path("toMethod").asText(""), call.path("fromMethod").asText("")); // 方法名。
            addEvidence(evidence, filePath, objectName, methodName, call.path("lineNumber").asInt(0), evidenceType); // 写入证据。
        }
        return evidence; // 返回证据。
    }

    private ArrayNode evidenceFromEndpoints(JsonNode endpoints, String controllerPath) { // 从接口数组构造证据。
        ArrayNode evidence = objectMapper.createArrayNode(); // 证据数组。
        if (!endpoints.isArray()) { // 非数组。
            return evidence; // 返回空。
        }
        for (JsonNode endpoint : endpoints) { // 遍历接口。
            if (evidence.size() >= MAX_EVIDENCE_PER_RISK) { // 达到上限。
                break; // 停止。
            }
            ObjectNode item = objectMapper.createObjectNode(); // 证据对象。
            putIfText(item, "filePath", firstNonBlank(controllerPath, endpoint.path("controllerPath").asText(""))); // 文件路径。
            putIfText(item, "endpoint", firstNonBlank(endpoint.path("path").asText(""), endpoint.path("endpoint").asText(""))); // 接口路径。
            putIfText(item, "httpMethod", endpoint.path("httpMethod").asText("")); // HTTP 方法。
            putIfText(item, "controllerMethod", firstNonBlank(endpoint.path("controllerMethod").asText(""), endpoint.path("methodName").asText(""))); // Controller 方法。
            putIfPositive(item, "lineNumber", endpoint.path("lineNumber").asInt(0)); // 行号。
            item.put("evidenceType", "CONTROLLER_ENDPOINT"); // 证据类型。
            if (item.size() > 1) { // 至少有目标信息。
                evidence.add(item); // 写入。
            }
        }
        return evidence; // 返回证据。
    }

    private ArrayNode evidenceFromSenders(JsonNode senders) { // 从 SSE 发送点构造证据。
        ArrayNode evidence = objectMapper.createArrayNode(); // 证据数组。
        if (!senders.isArray()) { // 非数组。
            return evidence; // 返回空。
        }
        for (JsonNode sender : senders) { // 遍历发送点。
            if (evidence.size() >= MAX_EVIDENCE_PER_RISK) { // 达到上限。
                break; // 停止。
            }
            ObjectNode item = objectMapper.createObjectNode(); // 证据对象。
            putIfText(item, "filePath", sender.path("senderFile").asText("")); // 文件路径。
            putIfText(item, "className", sender.path("senderClass").asText("")); // 类名。
            putIfText(item, "methodName", sender.path("senderMethod").asText("")); // 方法名。
            putIfText(item, "eventName", sender.path("eventName").asText("")); // 事件名。
            putIfPositive(item, "lineNumber", sender.path("lineNumber").asInt(0)); // 行号。
            item.put("evidenceType", "SSE_EVENT_SENDER"); // 证据类型。
            if (item.size() > 1) { // 有信息。
                evidence.add(item); // 写入。
            }
        }
        return evidence; // 返回证据。
    }

    private ArrayNode evidenceFromAiTool(JsonNode aiToolInfo, String path, String className, RiskState state) { // 从 AI Tool 信息构造证据。
        ArrayNode evidence = objectMapper.createArrayNode(); // 证据数组。
        if (!state.includeEvidence) { // 不需要证据。
            return evidence; // 返回空。
        }
        addEvidence(evidence, path, firstNonBlank(className, aiToolInfo.path("className").asText("")), "execute", 0, "AI_TOOL"); // execute 入口证据。
        return evidence; // 返回证据。
    }

    private ArrayNode classEvidence(JsonNode result, String path, RiskState state) { // 类声明证据。
        ArrayNode evidence = objectMapper.createArrayNode(); // 证据数组。
        if (!state.includeEvidence) { // 不需要证据。
            return evidence; // 返回空。
        }
        JsonNode classInfo = result.path("classInfo"); // 类信息。
        if (classInfo.isObject() && !classInfo.path("name").asText("").isBlank()) { // 有类名。
            addEvidence(evidence, path, classInfo.path("name").asText(""), null, classInfo.path("lineNumber").asInt(0), "CLASS_DECLARATION"); // 类声明证据。
        }
        return evidence; // 返回证据。
    }

    private void addGuardDependencyEvidence(JsonNode dependencies, String path, ArrayNode evidence, RiskState state) { // 补充 ProjectPathGuard 依赖证据。
        if (!state.includeEvidence || !dependencies.isArray()) { // 不需要或非数组。
            return; // 结束。
        }
        for (JsonNode dep : dependencies) { // 遍历依赖。
            if (lower(dep.path("type").asText("")).contains("projectpathguard")) { // 命中 ProjectPathGuard。
                addEvidence(evidence, path, dep.path("type").asText(""), dep.path("fieldName").asText(""), dep.path("lineNumber").asInt(0), "PATH_GUARD_DEPENDENCY"); // 依赖证据。
            }
        }
    }

    private void addEvidence(ArrayNode evidence, String filePath, String className, String methodName, int lineNumber, String evidenceType) { // 添加通用证据。
        if (evidence == null || evidence.size() >= MAX_EVIDENCE_PER_RISK) { // 已满。
            return; // 结束。
        }
        ObjectNode item = objectMapper.createObjectNode(); // 证据对象。
        putIfText(item, "filePath", filePath); // 文件路径。
        putIfText(item, "className", className); // 类名。
        putIfText(item, "methodName", methodName); // 方法名。
        putIfPositive(item, "lineNumber", lineNumber); // 行号（无行号不乱填）。
        item.put("evidenceType", evidenceType); // 证据类型。
        if (item.size() <= 1) { // 只有 evidenceType 没有任何目标信息。
            return; // 不写入空证据。
        }
        for (JsonNode existing : evidence) { // 去重。
            if (existing.toString().equals(item.toString())) { // 重复。
                return; // 跳过。
            }
        }
        evidence.add(item); // 写入证据。
    }

    private ArrayNode mergeEvidence(ArrayNode left, ArrayNode right, RiskState state) { // 合并两组证据并限量。
        ArrayNode merged = objectMapper.createArrayNode(); // 合并结果。
        if (!state.includeEvidence) { // 不需要证据。
            return merged; // 返回空。
        }
        for (JsonNode item : left) { // 复制左侧。
            if (merged.size() >= MAX_EVIDENCE_PER_RISK) { // 达到上限。
                break; // 停止。
            }
            merged.add(item); // 写入。
        }
        for (JsonNode item : right) { // 复制右侧。
            if (merged.size() >= MAX_EVIDENCE_PER_RISK) { // 达到上限。
                break; // 停止。
            }
            merged.add(item); // 写入。
        }
        return merged; // 返回合并结果。
    }

    // ===================== 风险/安全点收集与过滤 =====================

    private void addRisk(RiskState state, String title, String level, String category,
                         String description, ArrayNode evidence, String confidence) { // 添加风险点（带级别/类型过滤）。
        if (state.risks.size() >= state.maxItems) { // 达到上限。
            return; // 不再添加。
        }
        if (!state.levelAccepted(level)) { // 级别过滤。
            return; // 跳过。
        }
        if (!state.categoryAccepted(category)) { // 类型过滤。
            return; // 跳过。
        }
        ObjectNode risk = objectMapper.createObjectNode(); // 风险对象。
        risk.put("id", "RISK-" + String.format(Locale.ROOT, "%03d", state.risks.size() + 1)); // 临时 id，最终重排。
        risk.put("title", title); // 风险名称。
        risk.put("level", level); // 严重级别。
        risk.put("category", category); // 风险类型。
        risk.put("description", description); // 风险描述。
        risk.set("evidence", state.includeEvidence ? (evidence == null ? objectMapper.createArrayNode() : evidence) : objectMapper.createArrayNode()); // 证据。
        if (state.includeSuggestion) { // 仅在需要时给简短建议。
            risk.put("suggestion", suggestionFor(category)); // 说明级建议，不生成修改方案。
        }
        risk.put("confidence", confidence); // 置信度。
        state.risks.add(risk); // 收集风险。
    }

    private void addSafePoint(RiskState state, String title, String description, ArrayNode evidence) { // 添加已有保护点。
        if (state.safePoints.size() >= state.maxItems) { // 达到上限。
            return; // 不再添加。
        }
        ObjectNode safePoint = objectMapper.createObjectNode(); // 安全点对象。
        safePoint.put("title", title); // 标题。
        safePoint.put("description", description); // 描述。
        safePoint.set("evidence", state.includeEvidence ? (evidence == null ? objectMapper.createArrayNode() : evidence) : objectMapper.createArrayNode()); // 证据。
        state.safePoints.add(safePoint); // 收集安全点。
    }

    private ArrayNode reindexRisks(ArrayNode risks) { // 过滤后重排风险 id，保证连续。
        ArrayNode reindexed = objectMapper.createArrayNode(); // 重排结果。
        int index = 1; // 序号。
        for (JsonNode risk : risks) { // 遍历风险。
            if (risk instanceof ObjectNode objectNode) { // 是对象。
                objectNode.put("id", "RISK-" + String.format(Locale.ROOT, "%03d", index++)); // 重排 id。
                reindexed.add(objectNode); // 写入。
            }
        }
        return reindexed; // 返回重排结果。
    }

    private String suggestionFor(String category) { // 根据风险类型给说明级建议（不生成修改方案/patch）。
        return switch (category) { // 简短建议。
            case "PATH_TRAVERSAL" -> "保持所有 path 参数统一经过 ProjectPathGuard 校验，不在业务代码中直接拼接文件路径。"; // 路径穿越。
            case "DATABASE_WRITE" -> "建议人工确认写操作前的参数校验、事务和用户归属校验是否完整。"; // 数据库写。
            case "TOOL_CALLING" -> "建议确认 toolName 来源可控、有注册白名单，且工具调用失败有明确错误返回。"; // Tool Calling。
            case "PARAM_VALIDATION" -> "建议人工确认接口对前端参数的 null/权限校验以及统一返回处理。"; // 参数校验。
            case "SSE_STREAM" -> "建议人工确认 SSE 异常分支会发送 error 并 complete 流，避免连接泄漏。"; // SSE。
            case "FRONTEND_BACKEND_CONTRACT" -> "建议人工核对前后端事件名是否一致，前端是否处理 error/done。"; // 前后端契约。
            case "NULL_POINTER" -> "建议人工检查可能为空的对象在调用前是否有 null 判断。"; // 空指针。
            case "EXCEPTION_HANDLING" -> "建议人工确认关键分支有异常捕获和统一返回。"; // 异常处理。
            case "PERFORMANCE" -> "建议在高并发/超长输出场景关注连接数与吞吐。"; // 性能。
            case "LOGGING" -> "建议在关键路径补充必要日志，便于问题追踪。"; // 日志。
            case "MAINTAINABILITY" -> "建议人工评估是否按职责拆分类/方法，提取统一 helper。"; // 可维护性。
            default -> "建议人工结合上下文进一步确认该风险点。"; // 兜底。
        };
    }

    // ===================== 失败结果与通用辅助 =====================

    private ObjectNode buildFailureResult(CodeRiskScope riskScope, ObjectNode request, JsonNode primary) { // 构造风险失败 JSON。
        ObjectNode failure = objectMapper.createObjectNode(); // 失败对象。
        failure.put("type", RESULT_TYPE); // 内层 type 仍为 code_risk_analysis。
        failure.put("success", false); // 标记失败。
        failure.put("riskScope", riskScope.name()); // 写入风险范围。
        copyTargetFields(failure, request, primary); // 复制已知目标。
        failure.put("message", primary.path("message").asText("未找到真实匹配目标，无法生成风险点说明。")); // 透传底层失败原因。
        copyCandidateFields(failure, primary); // 复制真实候选，不捏造。
        failure.set("warnings", objectMapper.createArrayNode()); // 失败时 warnings 为空。
        return failure; // 返回失败 JSON。
    }

    private ObjectNode copyArguments(JsonNode arguments) { // 复制工具参数。
        return arguments instanceof ObjectNode objectNode ? objectNode.deepCopy() : objectMapper.createObjectNode(); // 非 object 入参按空对象处理。
    }

    private Set<String> parseRiskCategories(ObjectNode request) { // 解析 riskCategories 过滤集合。
        Set<String> categories = new LinkedHashSet<>(); // 结果集合。
        JsonNode node = request.path("riskCategories"); // 读取数组。
        if (node.isArray()) { // 是数组。
            for (JsonNode item : node) { // 遍历。
                String value = normalizeUpper(item.asText("")); // 归一化大写。
                if (!value.isBlank()) { // 非空。
                    categories.add(value); // 收集。
                }
            }
        } else if (node.isTextual() && !node.asText("").isBlank()) { // 兼容逗号分隔字符串。
            for (String part : node.asText().split("[,，\\s]+")) { // 拆分。
                String value = normalizeUpper(part); // 归一化。
                if (!value.isBlank()) { // 非空。
                    categories.add(value); // 收集。
                }
            }
        }
        return categories; // 返回集合（空表示不过滤）。
    }

    private Set<String> collectMethodNames(JsonNode calls) { // 收集调用数组中真实出现的方法名集合。
        Set<String> methods = new LinkedHashSet<>(); // 方法名集合。
        if (calls != null && calls.isArray()) { // 是数组。
            for (JsonNode call : calls) { // 遍历调用。
                String methodName = firstNonBlank(call.path("methodName").asText(""), call.path("toMethod").asText("")); // 方法名。
                if (!methodName.isBlank()) { // 非空。
                    methods.add(methodName); // 收集。
                }
            }
        }
        return methods; // 返回方法名集合。
    }

    private String buildGuardSafeDescription(Set<String> guardMethods) { // 根据真实 ProjectPathGuard 调用方法生成保护点说明。
        StringBuilder description = new StringBuilder("该 Tool 注入并调用了 ProjectPathGuard"); // 基础说明。
        if (guardMethods.contains("resolveProjectPath")) { // 命中 resolveProjectPath。
            description.append("，通过 resolveProjectPath 将相对路径约束在 workspace 内并拒绝绝对路径与路径穿越（..）"); // 路径穿越保护。
        }
        if (guardMethods.contains("validateReadableCodeFile")) { // 命中 validateReadableCodeFile。
            description.append("，通过 validateReadableCodeFile 校验文件类型、文件大小并只允许普通文件"); // 文件类型/大小保护。
        }
        if (guardMethods.contains("isSensitivePath") || guardMethods.contains("isBlockedFilename")
                || guardMethods.contains("isBlockedExtension")) { // 命中敏感文件判断。
            description.append("，过滤敏感文件与敏感目录（如 .git/target/node_modules/uploads/logs 等）"); // 敏感文件保护。
        }
        if (guardMethods.contains("toWorkspaceRelativePath")) { // 命中相对路径转换。
            description.append("，对外只返回 workspace 相对路径，不暴露服务器绝对路径"); // 不暴露绝对路径。
        }
        description.append("，路径穿越/敏感文件读取风险已有保护；后续新增读取入口时不应绕过 ProjectPathGuard。"); // 收尾提醒。
        return description.toString(); // 返回保护点说明。
    }

    private boolean hasDependencyType(JsonNode dependencies, String typeKeyword) { // 判断依赖中是否包含指定类型。
        if (!dependencies.isArray()) { // 非数组。
            return false; // 返回 false。
        }
        for (JsonNode dep : dependencies) { // 遍历依赖。
            if (lower(dep.path("type").asText("")).contains(typeKeyword.toLowerCase(Locale.ROOT))) { // 类型匹配。
                return true; // 命中。
            }
        }
        return false; // 未命中。
    }

    private Set<String> collectEventNames(JsonNode senders) { // 收集真实事件名集合。
        Set<String> events = new LinkedHashSet<>(); // 结果集合。
        if (senders.isArray()) { // 是数组。
            for (JsonNode sender : senders) { // 遍历。
                String eventName = sender.path("eventName").asText(""); // 事件名。
                if (!eventName.isBlank()) { // 非空。
                    events.add(eventName); // 收集。
                }
            }
        }
        return events; // 返回事件名集合。
    }

    private boolean hasUtilityReceiver(JsonNode utilityCalls, String receiver) { // 判断工具调用中是否包含指定接收者。
        if (!utilityCalls.isArray()) { // 非数组。
            return false; // 返回 false。
        }
        for (JsonNode call : utilityCalls) { // 遍历。
            if (receiver.equalsIgnoreCase(call.path("objectName").asText(""))) { // 命中接收者。
                return true; // 命中。
            }
        }
        return false; // 未命中。
    }

    private void copyTargetFields(ObjectNode target, ObjectNode request, JsonNode result) { // 复制轻量目标字段。
        putIfText(target, "path", firstNonBlank(text(request, "path"), text(result, "path"), text(result, "matchedControllerPath"))); // path。
        putIfText(target, "className", firstNonBlank(text(request, "className"), text(result, "className"), result.path("classInfo").path("name").asText(""), result.path("aiToolInfo").path("className").asText(""))); // className。
        putIfText(target, "methodName", firstNonBlank(text(request, "methodName"), text(result, "methodName"), text(result, "entryMethod"))); // methodName。
        putIfText(target, "endpoint", firstNonBlank(text(request, "endpoint"), text(result, "endpoint"))); // endpoint。
        putIfText(target, "toolName", firstNonBlank(text(request, "toolName"), text(result, "toolName"), result.path("aiToolInfo").path("toolName").asText(""))); // toolName。
        putIfText(target, "eventName", firstNonBlank(text(request, "eventName"), text(result, "eventName"))); // eventName。
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

    private JsonNode readJson(String json) throws Exception { // 安全解析 Analyzer 结果。
        return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json); // 空结果按空对象处理。
    }

    private boolean resolveBoolean(ObjectNode request, String fieldName, boolean defaultValue) { // 解析布尔参数。
        if (request == null || !request.hasNonNull(fieldName)) { // 缺失。
            return defaultValue; // 默认值。
        }
        return request.path(fieldName).asBoolean(defaultValue); // 解析。
    }

    private int resolveMaxItems(ObjectNode request) { // 解析风险点上限。
        int maxItems = getOptionalInt(request, "maxItems", DEFAULT_MAX_ITEMS); // 默认 100。
        if (maxItems <= 0) { // 非法值。
            return DEFAULT_MAX_ITEMS; // 返回默认。
        }
        return Math.min(maxItems, 300); // 最大 300。
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
        String lower = lower(pathOrClass.replace('\\', '/')); // 统一路径。
        return lower.endsWith("tool.java") || lower.endsWith("tool") || lower.contains("/tool/"); // Tool 文件/类/目录。
    }

    private String normalizeUpper(String value) { // 归一化大写。
        return value == null ? "" : value.trim().replace('-', '_').toUpperCase(Locale.ROOT); // 大写并兼容短横线。
    }

    private String lower(String value) { // 安全小写。
        return value == null ? "" : value.toLowerCase(Locale.ROOT); // 小写。
    }

    private String safe(String value) { // 安全展示短文本。
        if (value == null || value.isBlank()) { // 空值。
            return "-"; // 占位。
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim(); // 去换行。
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160) + "..."; // 控制长度。
    }

    private void putIfText(ObjectNode node, String fieldName, String value) { // 非空文本写入 JSON。
        if (node != null && value != null && !value.isBlank()) { // 有效值。
            node.put(fieldName, value); // 写入。
        }
    }

    private void putIfPositive(ObjectNode node, String fieldName, int value) { // 正整数写入 JSON（无行号不乱填）。
        if (node != null && value > 0) { // 有效行号。
            node.put(fieldName, value); // 写入。
        }
    }

    /**
     * 风险范围：决定复用哪个底层 Analyzer 以及如何生成风险点。
     */
    private enum CodeRiskScope { // 风险范围枚举。
        AUTO, // 自动按参数推断。
        CLASS, // 单个类/文件风险。
        METHOD, // 方法风险。
        CONTROLLER_ENDPOINT, // Controller 接口风险。
        TOOL_CHAIN, // AI Tool 执行链路风险。
        CALL_CHAIN, // 普通调用链风险。
        SSE_CHAIN; // 前后端 SSE 链路风险。

        private static CodeRiskScope from(String rawValue) { // 解析 riskScope 字符串。
            if (rawValue == null || rawValue.isBlank()) { // 未传。
                return AUTO; // 默认 AUTO。
            }
            try {
                return CodeRiskScope.valueOf(rawValue.trim().replace('-', '_').toUpperCase(Locale.ROOT)); // 解析枚举。
            } catch (IllegalArgumentException ex) {
                return AUTO; // 非法值兜底。
            }
        }
    }

    /**
     * 风险收集状态：汇总过滤条件、风险点和安全点。
     */
    private final class RiskState { // 风险收集器（内部类便于直接构造 JSON 节点）。
        private final String riskLevel; // 风险级别过滤（ALL 或具体级别）。
        private final Set<String> riskCategories; // 风险类型过滤（空表示不过滤）。
        private final boolean includeEvidence; // 是否返回证据。
        private final boolean includeSuggestion; // 是否返回建议。
        private final boolean includeSnippet; // 是否返回代码片段。
        private final int maxItems; // 风险点上限。
        private final ArrayNode risks = objectMapper.createArrayNode(); // 风险点列表。
        private final ArrayNode safePoints = objectMapper.createArrayNode(); // 安全点列表。

        private RiskState(String riskLevel, Set<String> riskCategories, boolean includeEvidence,
                          boolean includeSuggestion, boolean includeSnippet, int maxItems) { // 构造风险状态。
            this.riskLevel = riskLevel == null || riskLevel.isBlank() ? "ALL" : riskLevel; // 默认 ALL。
            this.riskCategories = riskCategories; // 保存类型过滤。
            this.includeEvidence = includeEvidence; // 保存证据开关。
            this.includeSuggestion = includeSuggestion; // 保存建议开关。
            this.includeSnippet = includeSnippet; // 保存片段开关。
            this.maxItems = maxItems <= 0 ? DEFAULT_MAX_ITEMS : maxItems; // 保存上限。
        }

        private boolean levelAccepted(String level) { // 判断风险级别是否通过过滤。
            return "ALL".equals(riskLevel) || riskLevel.equalsIgnoreCase(level); // ALL 或匹配级别。
        }

        private boolean categoryAccepted(String category) { // 判断风险类型是否通过过滤。
            return riskCategories == null || riskCategories.isEmpty() || riskCategories.contains(category); // 空集合不过滤。
        }
    }
}

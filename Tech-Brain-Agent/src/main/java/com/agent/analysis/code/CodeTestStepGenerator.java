package com.agent.analysis.code; // 项目代码内部分析器包。

import com.agent.toolcalling.project.analysis.CodeAnalysisType; // analyzeCode 统一分析类型。
import com.fasterxml.jackson.databind.JsonNode; // 读取已有 Analyzer 的真实分析结果。
import com.fasterxml.jackson.databind.node.ArrayNode; // 构造 testCases/logChecks/regressionChecks/evidence 数组。
import com.fasterxml.jackson.databind.node.ObjectNode; // 构造 code_test_steps 结构化结果。
import org.springframework.stereotype.Component; // 注册为内部 Spring Bean，供 AnalyzeCodeTool 分发。

import java.util.Locale; // 解析 testScope/testType 和风险类型。

/**
 * P5.8 代码测试步骤内部生成器。
 *
 * <p>适用场景：用户要求“怎么测试 / 生成测试步骤 / 测试清单 / 回归测试 / 接口测试 / Tool 测试 / SSE 测试 /
 * 日志验证”时，对外仍然只调用 analyzeCode，AnalyzeCodeTool 根据 analysisType=TEST_STEPS 分发到本组件。
 * 本组件不会注册为 AI Tool，而是复用已有 CodeStructureAnalyzer、CallChainAnalyzer、ControllerServiceChainAnalyzer、
 * ToolServiceChainAnalyzer、SseEventChainAnalyzer 和 CodeRiskAnalyzer 的真实扫描结果，把类、方法、接口、Tool、
 * SSE 事件和风险证据转换成开发者可执行的手动测试步骤。</p>
 *
 * <p>调用链：ToolCallingChatServiceImpl 构造 analyzeCode arguments（analysisType=TEST_STEPS、testScope、testType）
 * -> AnalyzeCodeTool 规范化 recentProjectTarget/projectFileFocus 和目标参数 -> CodeTestStepGenerator.execute(arguments)
 * -> 根据 testScope 调用对应内部 Analyzer 获取真实结果 -> 可选调用 CodeRiskAnalyzer 获取风险点
 * -> 生成 testCases、logChecks、regressionChecks 和 evidence -> 返回 code_test_steps 作为 code_analysis.result。</p>
 *
 * <p>边界说明：本组件只生成静态测试步骤，不自动执行测试、不调用接口、不执行 SQL、不输出完整源码、不生成修改方案或 patch；
 * 没有扫描到真实类、接口、Tool 或事件时返回失败，不根据命名猜测不存在的测试对象。</p>
 */
@Component // 仅作为 analyzeCode 内部 Handler 注入，不会被 ToolRegistry 暴露为对外 Tool。
public class CodeTestStepGenerator extends AbstractCodeAnalysisHandler { // P5.8 测试步骤内部生成器。
    private static final String RESULT_TYPE = "code_test_steps"; // 内层结果类型。
    private static final int DEFAULT_MAX_ITEMS = 100; // 默认最多返回测试用例数量。
    private static final int MAX_ALLOWED_ITEMS = 300; // 最大测试用例数量。
    private static final int MAX_EVIDENCE_ITEMS = 8; // 单个测试用例最多证据数量。

    private final CodeStructureAnalyzer codeStructureAnalyzer; // 复用单文件结构分析能力。
    private final CallChainAnalyzer callChainAnalyzer; // 复用普通调用链分析能力。
    private final ControllerServiceChainAnalyzer controllerServiceChainAnalyzer; // 复用 Controller→Service 分析能力。
    private final ToolServiceChainAnalyzer toolServiceChainAnalyzer; // 复用 Tool→业务组件分析能力。
    private final SseEventChainAnalyzer sseEventChainAnalyzer; // 复用 SSE 事件链路分析能力。
    private final CodeRiskAnalyzer codeRiskAnalyzer; // 复用 P5.7 风险点结果生成重点测试场景。

    public CodeTestStepGenerator(CodeStructureAnalyzer codeStructureAnalyzer,
                                 CallChainAnalyzer callChainAnalyzer,
                                 ControllerServiceChainAnalyzer controllerServiceChainAnalyzer,
                                 ToolServiceChainAnalyzer toolServiceChainAnalyzer,
                                 SseEventChainAnalyzer sseEventChainAnalyzer,
                                 CodeRiskAnalyzer codeRiskAnalyzer) { // 注入已有内部 Analyzer，避免重复实现扫描逻辑。
        this.codeStructureAnalyzer = codeStructureAnalyzer; // 保存结构分析器。
        this.callChainAnalyzer = callChainAnalyzer; // 保存调用链分析器。
        this.controllerServiceChainAnalyzer = controllerServiceChainAnalyzer; // 保存 Controller 专项分析器。
        this.toolServiceChainAnalyzer = toolServiceChainAnalyzer; // 保存 Tool 专项分析器。
        this.sseEventChainAnalyzer = sseEventChainAnalyzer; // 保存 SSE 专项分析器。
        this.codeRiskAnalyzer = codeRiskAnalyzer; // 保存风险分析器。
    }

    @Override
    public CodeAnalysisType analysisType() { // 返回 AnalyzeCodeTool 分发键。
        return CodeAnalysisType.TEST_STEPS; // P5.8 测试步骤生成。
    }

    @Override
    public String execute(JsonNode arguments) { // 测试步骤生成入口。
        ObjectNode request = copyArguments(arguments); // 复制入参，避免污染外层 tool_call_log。
        CodeTestScope testScope = resolveTestScope(request); // 解析或自动判断测试范围。
        CodeTestType testType = CodeTestType.from(getOptionalText(request, "testType", "ALL")); // 解析测试类型过滤。
        boolean includeRiskCases = resolveBoolean(request, "includeRiskCases", true); // 默认结合风险点生成重点场景。
        boolean includeLogChecks = resolveBoolean(request, "includeLogChecks", true); // 默认包含 tool_call_log / 后端日志验证。
        boolean includeExpectedResult = resolveBoolean(request, "includeExpectedResult", true); // 默认包含预期结果。
        boolean includeRequestExample = resolveBoolean(request, "includeRequestExample", true); // 默认包含请求示例。
        boolean includeSnippet = resolveBoolean(request, "includeSnippet", false); // 测试步骤默认不贴代码片段。
        int maxItems = resolveMaxItems(request); // 解析测试用例上限。

        ObjectNode analysisRequest = request.deepCopy(); // 底层 Analyzer 使用独立入参。
        prepareAnalyzerArguments(analysisRequest, testScope, includeSnippet); // 设置底层分析类型、深度和 className 兜底。
        JsonNode primary = runAnalysis(testScope, analysisRequest); // 获取真实分析结果。
        if (!primary.path("success").asBoolean(false)) { // 未找到真实测试对象。
            return buildFailureResult(testScope, testType, request, primary).toString(); // 不捏造测试步骤。
        }

        JsonNode risks = includeRiskCases ? runRiskAnalysis(request, testScope) : objectMapper.createObjectNode(); // 可选风险点结果。
        TestStepState state = new TestStepState(testType, includeExpectedResult, includeRequestExample, maxItems); // 测试用例收集状态。
        ObjectNode result = objectMapper.createObjectNode(); // 构造 code_test_steps。
        result.put("type", RESULT_TYPE); // 写入内层 type。
        result.put("success", true); // 标记成功。
        result.put("testScope", testScope.name()); // 写入最终测试范围。
        result.put("testType", testType.name()); // 写入测试类型过滤。
        copyTargetFields(result, request, primary); // 复制 path/className/endpoint/toolName/eventName 等轻量目标字段。
        result.put("summary", buildSummary(testScope, primary)); // 一句话测试目标。

        ArrayNode preconditions = objectMapper.createArrayNode(); // 前置条件。
        ArrayNode logChecks = objectMapper.createArrayNode(); // 日志验证。
        ArrayNode regressionChecks = objectMapper.createArrayNode(); // 回归验证。
        ArrayNode warnings = objectMapper.createArrayNode(); // 生成边界说明。
        fillPreconditions(testScope, primary, preconditions); // 从真实目标生成前置条件。
        fillScopeTestCases(testScope, primary, risks, state); // 从主分析结果生成范围测试用例。
        if (includeRiskCases) { // 用户允许结合风险点。
            fillRiskTestCases(risks, state); // 将真实风险点转换成重点测试场景。
        }
        if (includeLogChecks) { // 用户允许输出日志验证。
            fillLogChecks(testScope, request, primary, logChecks); // 生成 tool_call_log / 后端日志验证项。
        }
        fillRegressionChecks(testScope, regressionChecks); // 生成 P4/P5 回归项。
        warnings.add("测试步骤基于项目源码静态扫描结果生成，不会自动执行接口、测试或 SQL；confidence=MEDIUM/LOW 的场景需要人工核查。"); // 统一边界。
        copyWarnings(primary, warnings); // 透传底层 Analyzer 的不确定项。
        copyWarnings(risks, warnings); // 透传风险分析的不确定项。

        result.set("preconditions", preconditions); // 写入前置条件。
        result.set("testCases", state.testCases); // 写入测试用例。
        result.set("logChecks", logChecks); // 写入日志检查。
        result.set("regressionChecks", regressionChecks); // 写入回归检查。
        result.set("warnings", warnings); // 写入 warnings。
        return result.toString(); // 返回内层 JSON 字符串。
    }

    // ===================== testScope 与底层 Analyzer 复用 =====================

    private CodeTestScope resolveTestScope(ObjectNode request) { // 解析测试范围。
        CodeTestScope requestedScope = CodeTestScope.from(getOptionalText(request, "testScope", "AUTO")); // 显式 testScope。
        if (requestedScope != CodeTestScope.AUTO) { // 显式范围优先。
            return requestedScope; // 返回显式范围。
        }
        if (hasText(request, "eventName") || hasText(request, "frontendKeyword")) { // SSE 事件或前端关键词。
            return CodeTestScope.SSE_CHAIN; // SSE 链路测试。
        }
        if (hasText(request, "endpoint")) { // 接口路径。
            return CodeTestScope.CONTROLLER_ENDPOINT; // Controller 接口测试。
        }
        if (hasText(request, "toolName") || looksLikeToolTarget(firstText(request, "path", "className"))) { // Tool 名或 Tool 文件。
            return CodeTestScope.TOOL_CHAIN; // AI Tool 测试。
        }
        if (hasText(request, "methodName")) { // 有方法名。
            return CodeTestScope.METHOD; // 方法测试。
        }
        return CodeTestScope.CLASS; // 默认类/文件测试。
    }

    private void prepareAnalyzerArguments(ObjectNode request, CodeTestScope testScope, boolean includeSnippet) { // 设置底层 Analyzer 参数。
        request.put("analysisType", mappedAnalysisType(testScope).name()); // 底层 Analyzer 只看到对应分析类型。
        request.put("includeSnippet", includeSnippet); // 测试步骤默认不贴片段。
        if (!request.hasNonNull("maxItems")) { // 缺少上限时使用默认值。
            request.put("maxItems", DEFAULT_MAX_ITEMS); // 默认 100。
        }
        int depth = (testScope == CodeTestScope.CONTROLLER_ENDPOINT
                || testScope == CodeTestScope.TOOL_CHAIN
                || testScope == CodeTestScope.SSE_CHAIN) ? 2 : 1; // 链路类测试需要展开一层。
        request.put("maxDepth", depth); // 写入深度。
        if (!hasText(request, "path") && hasText(request, "className")
                && (testScope == CodeTestScope.CLASS
                || testScope == CodeTestScope.METHOD
                || testScope == CodeTestScope.CALL_CHAIN
                || testScope == CodeTestScope.CONTROLLER_ENDPOINT
                || testScope == CodeTestScope.TOOL_CHAIN)) { // 旧 Analyzer 通过 path 字段支持类名定位。
            request.put("path", request.path("className").asText()); // 将 className 作为定位目标。
        }
    }

    private JsonNode runAnalysis(CodeTestScope testScope, ObjectNode request) { // 执行底层真实分析。
        try {
            return readJson(analyzerFor(testScope).analyze(request)); // 调用对应内部 Analyzer。
        } catch (Exception e) {
            ObjectNode failure = objectMapper.createObjectNode(); // 构造失败对象。
            failure.put("type", "analysis_failure"); // 标记内部失败。
            failure.put("success", false); // 执行失败。
            failure.put("message", "未找到真实匹配目标，无法生成测试步骤。"); // 不暴露内部异常。
            return failure; // 返回失败。
        }
    }

    private JsonNode runRiskAnalysis(ObjectNode request, CodeTestScope testScope) { // 调用风险分析器生成重点测试依据。
        try {
            ObjectNode riskRequest = request.deepCopy(); // 风险分析使用独立入参。
            riskRequest.put("analysisType", CodeAnalysisType.RISK.name()); // 指定风险分析。
            riskRequest.put("riskScope", mappedRiskScope(testScope)); // 测试范围映射风险范围。
            riskRequest.put("riskLevel", "ALL"); // 默认获取全部风险级别。
            riskRequest.put("includeEvidence", true); // 风险转测试需要证据。
            riskRequest.put("includeSuggestion", false); // 测试步骤不需要修改建议。
            riskRequest.put("includeSnippet", false); // 不返回代码片段。
            riskRequest.put("maxItems", Math.min(resolveMaxItems(request), 50)); // 风险点数量适当收敛。
            return readJson(codeRiskAnalyzer.analyze(riskRequest)); // 调用已有 CodeRiskAnalyzer。
        } catch (Exception e) {
            ObjectNode failure = objectMapper.createObjectNode(); // 风险分析失败不影响主测试步骤。
            failure.put("success", false); // 标记失败。
            failure.put("message", "未能结合风险点生成测试场景。"); // 简短说明。
            return failure; // 返回失败风险结果。
        }
    }

    private CodeAnalysisHandler analyzerFor(CodeTestScope testScope) { // 测试范围映射到底层 Analyzer。
        return switch (testScope) { // 复用已有 P5 分析能力。
            case CLASS -> codeStructureAnalyzer; // 类/文件测试基于结构分析。
            case METHOD, CALL_CHAIN -> callChainAnalyzer; // 方法和普通调用链测试基于调用链。
            case CONTROLLER_ENDPOINT -> controllerServiceChainAnalyzer; // 接口测试基于 Controller→Service。
            case TOOL_CHAIN -> toolServiceChainAnalyzer; // Tool 测试基于 Tool→Service。
            case SSE_CHAIN -> sseEventChainAnalyzer; // SSE 测试基于 SSE 链路。
            case AUTO -> codeStructureAnalyzer; // 理论不会进入，兜底结构分析。
        };
    }

    private CodeAnalysisType mappedAnalysisType(CodeTestScope testScope) { // 测试范围映射到底层 analysisType。
        return switch (testScope) {
            case CLASS -> CodeAnalysisType.STRUCTURE; // 结构。
            case METHOD, CALL_CHAIN -> CodeAnalysisType.CALL_CHAIN; // 调用链。
            case CONTROLLER_ENDPOINT -> CodeAnalysisType.CONTROLLER_SERVICE; // Controller→Service。
            case TOOL_CHAIN -> CodeAnalysisType.TOOL_SERVICE; // Tool→Service。
            case SSE_CHAIN -> CodeAnalysisType.SSE_EVENT_CHAIN; // SSE 链路。
            case AUTO -> CodeAnalysisType.STRUCTURE; // 兜底。
        };
    }

    private String mappedRiskScope(CodeTestScope testScope) { // 测试范围映射到风险范围参数。
        return switch (testScope) {
            case METHOD -> "METHOD"; // 方法风险。
            case CALL_CHAIN -> "CALL_CHAIN"; // 调用链风险。
            case CONTROLLER_ENDPOINT -> "CONTROLLER_ENDPOINT"; // 接口风险。
            case TOOL_CHAIN -> "TOOL_CHAIN"; // Tool 风险。
            case SSE_CHAIN -> "SSE_CHAIN"; // SSE 风险。
            case CLASS, AUTO -> "CLASS"; // 类/文件风险。
        };
    }

    // ===================== 测试用例生成 =====================

    private void fillPreconditions(CodeTestScope testScope, JsonNode result, ArrayNode preconditions) { // 生成前置条件。
        preconditions.add("确认本次测试对象来自工具扫描结果中的真实文件、接口、Tool 或事件，不使用猜测路径。"); // 真实目标约束。
        String path = firstNonBlank(text(result, "path"), text(result, "matchedControllerPath")); // 文件路径。
        if (!path.isBlank()) { // 有文件路径。
            preconditions.add("确认项目 workspace 中存在文件：" + safe(path)); // 文件前置。
        }
        if (testScope == CodeTestScope.CONTROLLER_ENDPOINT) { // 接口测试。
            preconditions.add("准备可访问后端服务和 Swagger/Postman/curl 等接口调试方式。"); // API 前置。
        }
        if (testScope == CodeTestScope.SSE_CHAIN) { // SSE 测试。
            preconditions.add("准备可观察 text/event-stream 输出和前端事件处理日志的调试环境。"); // SSE 前置。
        }
        if (testScope == CodeTestScope.TOOL_CHAIN) { // Tool 测试。
            preconditions.add("准备聊天窗口和工具调用详情页，用于检查 tool_call_log、arguments_json 与 result_json。"); // Tool 前置。
        }
    }

    private void fillScopeTestCases(CodeTestScope testScope, JsonNode result, JsonNode risks, TestStepState state) { // 按范围生成测试用例。
        switch (testScope) { // 分范围处理。
            case CONTROLLER_ENDPOINT -> fillControllerTestCases(result, risks, state); // 接口测试。
            case TOOL_CHAIN -> fillToolTestCases(result, risks, state); // Tool 测试。
            case SSE_CHAIN -> fillSseTestCases(result, state); // SSE 测试。
            case METHOD, CALL_CHAIN -> fillCallChainTestCases(result, state); // 方法/调用链测试。
            case CLASS, AUTO -> fillClassTestCases(result, state); // 类/文件测试。
        }
    }

    private void fillClassTestCases(JsonNode result, TestStepState state) { // 生成类/文件测试步骤。
        String className = firstNonBlank(result.path("classInfo").path("name").asText(""), text(result, "className"), text(result, "fileName")); // 类名。
        String path = text(result, "path"); // 文件路径。
        addTestCase(state, "类结构基础验证", "MANUAL", "MEDIUM", "HIGH",
                steps("打开 analyzeCode 工具调用详情，确认 analysisType=TEST_STEPS。",
                        "检查 result_json.target.path 或 result.path 是否指向 " + safe(path) + "。",
                        "核对类名、字段、方法和注解是否与代码结构分析结果一致。"),
                expected("能够看到 " + safe(className) + " 的真实结构信息。",
                        "测试步骤未输出完整源码，也未出现服务器绝对路径。"),
                evidenceFromResult(result, "CLASS_DECLARATION"), null); // 类结构验证。
        if (result.path("methods").isArray() && result.path("methods").size() > 0) { // 有方法列表。
            addTestCase(state, "关键方法可达性验证", "MANUAL", "MEDIUM", "HIGH",
                    steps("从 methods 中选取入口或公开方法。",
                            "分别触发对应功能或通过 Swagger/聊天入口间接调用。",
                            "记录每个方法的正常返回、异常返回和日志表现。"),
                    expected("关键方法都有可观测的正常路径和失败路径。",
                            "未扫描到的方法不纳入测试结论。"),
                    evidenceFromArray(result.path("methods"), path, "METHOD_DECLARATION"), null); // 方法验证。
        }
        if (result.path("springEndpoints").isArray() && result.path("springEndpoints").size() > 0) { // Controller 结构中有接口。
            addTestCase(state, "Controller 接口摘要回归", "API", "HIGH", "HIGH",
                    steps("根据 springEndpoints 中的 HTTP 方法和路径发起请求。",
                            "分别验证正常请求、缺少参数和异常请求。",
                            "检查返回结构是否符合项目统一响应或 SSE 响应。"),
                    expected("扫描到的接口可以被真实请求覆盖。",
                            "未出现在 springEndpoints 中的接口不作为本轮测试对象。"),
                    evidenceFromArray(result.path("springEndpoints"), path, "SPRING_ENDPOINT"), requestExampleForEndpoint(firstEndpoint(result))); // Controller 摘要测试。
        }
        if (result.path("aiToolInfo").path("isAiTool").asBoolean(false)) { // AI Tool 文件。
            addToolRouteCase(result, state); // 补 Tool Calling 验证。
        }
    }

    private void fillCallChainTestCases(JsonNode result, TestStepState state) { // 生成方法/调用链测试步骤。
        String entryMethod = firstNonBlank(text(result, "entryMethod"), text(result, "methodName")); // 入口方法。
        addTestCase(state, "入口方法正常路径验证", "MANUAL", "HIGH", "MEDIUM",
                steps("定位入口方法 " + safe(entryMethod) + " 对应的真实功能入口。",
                        "使用正常参数触发该方法所在功能。",
                        "记录 internalCalls、externalCalls 中关键调用是否按预期发生。"),
                expected("入口方法能完成主流程，关键内部调用和外部依赖调用可通过日志或返回结果间接验证。",
                        "candidateTargets 只作为候选目标，不作为绝对结论。"),
                evidenceFromCalls(result, "CALL_EXPRESSION"), null); // 正常调用链。
        if (result.path("dependencies").isArray() && result.path("dependencies").size() > 0) { // 有依赖对象。
            addTestCase(state, "依赖对象异常路径验证", "REGRESSION", "MEDIUM", "MEDIUM",
                    steps("针对 dependencies 中的外部依赖，构造依赖失败或返回空值的场景。",
                            "观察入口方法是否给出可读错误或安全兜底。",
                            "检查日志是否能定位到失败依赖和方法名。"),
                    expected("依赖失败不会导致不可控异常或空白响应。",
                            "静态分析无法确认模拟方式，需要结合实际运行环境人工验证。"),
                    evidenceFromArray(result.path("dependencies"), text(result, "path"), "DEPENDENCY_DECLARATION"), null); // 依赖异常。
        }
    }

    private void fillControllerTestCases(JsonNode result, JsonNode risks, TestStepState state) { // 生成 Controller 接口测试步骤。
        String endpoint = firstNonBlank(text(result, "endpoint"), firstEndpointPath(result.path("endpoints"))); // endpoint。
        String httpMethod = firstHttpMethod(result.path("endpoints")); // HTTP 方法。
        addTestCase(state, "接口正常请求", "API", "HIGH", "HIGH",
                steps("在 Swagger/Postman/curl 中请求 " + safe(httpMethod) + " " + safe(endpoint) + "。",
                        "按 Controller 方法声明准备正常请求参数、请求体或 path variable。",
                        "观察返回结构、HTTP 状态和后端日志。"),
                expected("接口能够返回成功响应或项目统一成功结构。",
                        "若该接口是 SSE，应返回 text/event-stream，并持续输出事件直到 done 或完成。"),
                evidenceFromEndpoints(result), requestExampleForEndpoint(result)); // 正常请求。
        addTestCase(state, "参数边界与异常请求", "API", "HIGH", "MEDIUM",
                steps("分别构造缺少必填参数、空 request body、非法 path variable 和超长字段。",
                        "发送请求并记录返回结构。",
                        "检查 Controller 或 Service 是否返回可读错误，而不是服务器内部异常细节。"),
                expected("异常输入能够得到明确错误响应。",
                        "如果静态扫描未发现参数校验证据，需要人工检查接口入参校验。"),
                mergeEvidence(evidenceFromEndpoints(result), evidenceFromRisks(risks, "PARAM_VALIDATION")), requestExampleForEndpoint(result)); // 参数边界。
        if (result.path("serviceCalls").isArray() && result.path("serviceCalls").size() > 0) { // 有 Service 调用。
            addTestCase(state, "Controller 到 Service 调用验证", "REGRESSION", "MEDIUM", "HIGH",
                    steps("触发接口正常请求。",
                            "通过日志、断点或可观测结果确认 Controller 方法调用到扫描出的 Service 方法。",
                            "maxDepth=2 时同步核对 ServiceImpl 内部一层调用候选。"),
                    expected("Controller 不直接承载复杂业务，核心流程进入扫描出的 Service 候选链路。",
                            "ServiceImpl 目标为候选结果，需要人工核对真实实现类。"),
                    evidenceFromArray(result.path("serviceCalls"), firstNonBlank(text(result, "path"), text(result, "matchedControllerPath")), "SERVICE_CALL"), null); // Service 调用。
        }
    }

    private void fillToolTestCases(JsonNode result, JsonNode risks, TestStepState state) { // 生成 AI Tool 测试步骤。
        addToolRouteCase(result, state); // 基础 Tool 路由验证。
        addTestCase(state, "Tool 参数缺失和失败路径", "TOOL_CALLING", "HIGH", "HIGH",
                steps("在聊天中输入缺少必要目标的 Tool 场景，例如只说“读取文件”或“分析它”。",
                        "打开工具调用详情，检查 arguments_json 是否只包含真实可解析目标。",
                        "检查 result_json.success=false 时是否返回清晰 message/candidates。"),
                expected("Tool 不会根据 toolName、类名或路径脑补不存在文件。",
                        "失败时 finalAnswer 基于 result_json.message 给出可读提示。"),
                evidenceFromResult(result, "TOOL_EXECUTE"), null); // 参数缺失。
        if (hasGuardEvidence(result) || hasRiskCategory(risks, "PATH_TRAVERSAL")) { // 路径安全类 Tool。
            addTestCase(state, "路径穿越与敏感文件边界", "SECURITY", "HIGH", "HIGH",
                    steps("分别输入 ../、../../application.yml、绝对路径和 .env 等敏感目标。",
                            "检查 arguments_json 是否保持 workspace 相对目标或被拒绝。",
                            "检查 result_json 是否拒绝 workspace 外路径、敏感目录和敏感文件。"),
                    expected("ProjectPathGuard 或等价安全组件拒绝路径穿越、绝对路径和敏感文件读取。",
                            "返回内容不得包含服务器绝对路径、密钥或敏感配置正文。"),
                    mergeEvidence(evidenceFromArray(result.path("guardCalls"), text(result, "path"), "PATH_GUARD_CALL"),
                            evidenceFromRisks(risks, "PATH_TRAVERSAL")), "读取 ../../application.yml"); // 路径安全。
        }
        if (result.path("mapperCalls").size() > 0 || result.path("repositoryCalls").size() > 0) { // 有数据库候选调用。
            addTestCase(state, "数据库相关调用验证", "REGRESSION", "MEDIUM", "MEDIUM",
                    steps("触发 Tool 正常执行路径。",
                            "确认扫描出的 Mapper/Repository 候选调用是否真实发生。",
                            "检查失败路径不会产生不完整写入或重复写入。"),
                    expected("数据库相关调用仅在真实业务路径发生。",
                            "静态分析只给候选，需要人工结合 ServiceImpl/Mapper 验证。"),
                    mergeEvidence(evidenceFromArray(result.path("mapperCalls"), text(result, "path"), "MAPPER_CALL"),
                            evidenceFromArray(result.path("repositoryCalls"), text(result, "path"), "REPOSITORY_CALL")), null); // 数据库候选。
        }
    }

    private void addToolRouteCase(JsonNode result, TestStepState state) { // 添加 Tool Calling 基础验证。
        String toolName = firstNonBlank(result.path("aiToolInfo").path("toolName").asText(""), text(result, "toolName")); // toolName。
        addTestCase(state, "Tool 路由和 result_json 验证", "TOOL_CALLING", "HIGH", "HIGH",
                steps("在聊天中输入能触发 " + safe(toolName) + " 的真实用户问题。",
                        "打开工具调用详情。",
                        "检查 tool_name、arguments_json 和 result_json.type 是否与该 Tool 预期一致。",
                        "检查 finalAnswer 是否基于工具结果输出，不泄漏 DSML/tool_calls。"),
                expected("工具调用成功进入对应 Tool。",
                        "arguments_json 只包含真实解析出的 path/toolName/className/methodName 等参数。",
                        "tool_call_log 中 final_answer 最终被回填。"),
                evidenceFromResult(result, "AI_TOOL"), requestExampleForTool(toolName)); // Tool 路由。
    }

    private void fillSseTestCases(JsonNode result, TestStepState state) { // 生成 SSE 链路测试步骤。
        String endpoint = firstNonBlank(text(result, "endpoint"), firstEndpointPath(result.path("backendSseEndpoints"))); // SSE endpoint。
        String eventName = firstNonBlank(text(result, "eventName"), firstEventName(result)); // 事件名。
        addTestCase(state, "SSE 正常事件流", "SSE", "HIGH", "HIGH",
                steps("从前端或接口工具发起 " + safe(endpoint) + " 的流式请求。",
                        "确认响应 Content-Type 为 text/event-stream 或前端以 ReadableStream/EventSource 处理。",
                        "观察是否接收到 " + safe(eventName) + "、tool_call/tool_result、done 等真实扫描到的事件。"),
                expected("后端持续推送事件，最终发送 done 或正常完成。",
                        "前端接收逻辑能按事件名更新页面状态。"),
                evidenceFromSse(result), requestExampleForEndpoint(result)); // 正常 SSE。
        addTestCase(state, "SSE 异常中断与 error 事件", "SSE", "HIGH", "MEDIUM",
                steps("模拟后端处理异常、网络断开或用户取消连接。",
                        "观察后端是否发送 error 事件或 complete 连接。",
                        "检查前端是否清理 loading 状态并展示可读错误。"),
                expected("异常路径不会导致连接悬挂、无限 loading 或重复 finalAnswer。",
                        "静态分析无法完全确认断连行为，需要人工用浏览器和后端日志验证。"),
                evidenceFromSse(result), null); // 异常 SSE。
        if (!result.path("frontendEventHandlers").isArray() || result.path("frontendEventHandlers").size() == 0) { // 未扫描到前端处理。
            addTestCase(state, "前端事件接收缺失核查", "SSE", "MEDIUM", "LOW",
                    steps("人工搜索前端是否处理 " + safe(eventName) + " 事件。",
                            "确认没有遗漏 summary_result/tool_call/tool_result/done/error 等事件分支。",
                            "如果前端确实未处理，记录为需要补充验证的缺口。"),
                    expected("能够明确前端是否存在事件处理逻辑。",
                            "本项来自静态扫描未命中，不能直接断言前端缺失。"),
                    objectMapper.createArrayNode(), null); // 前端缺失核查。
        }
    }

    private void fillRiskTestCases(JsonNode riskResult, TestStepState state) { // 将风险点转换为测试场景。
        JsonNode risks = riskResult.path("risks"); // 风险数组。
        if (!risks.isArray()) { // 没有风险数组。
            return; // 结束。
        }
        for (JsonNode risk : risks) { // 遍历风险点。
            if (state.testCases.size() >= state.maxItems) { // 达到上限。
                return; // 结束。
            }
            String category = risk.path("category").asText(""); // 风险类型。
            String type = riskTestType(category); // 测试类型。
            if (!state.accepts(type)) { // testType 过滤。
                continue; // 跳过。
            }
            addTestCase(state, "风险点对应测试：" + safe(risk.path("title").asText(category)), type,
                    priorityFromRiskLevel(risk.path("level").asText("")), risk.path("confidence").asText("MEDIUM"),
                    stepsForRisk(category, risk), expectedForRisk(category), limitedEvidence(risk.path("evidence")), risk.path("id").asText("")); // 风险测试。
        }
    }

    private void fillLogChecks(CodeTestScope testScope, ObjectNode request, JsonNode result, ArrayNode logChecks) { // 生成日志验证项。
        ObjectNode analyzeLog = objectMapper.createObjectNode(); // analyzeCode 工具日志。
        analyzeLog.put("type", "TOOL_CALL_LOG"); // 日志类型。
        analyzeLog.put("description", "检查测试步骤生成本身是否统一记录为 analyzeCode。"); // 描述。
        ArrayNode expected = objectMapper.createArrayNode(); // 期望。
        expected.add("tool_name = analyzeCode"); // 统一工具名。
        expected.add("tool_type = PROJECT"); // 项目工具类型。
        expected.add("arguments_json.analysisType = TEST_STEPS"); // 分析类型。
        expected.add("result_json.type = code_analysis"); // 外层类型。
        expected.add("result_json.result.type = code_test_steps"); // 内层类型。
        expected.add("final_answer 已回填"); // 最终回答。
        analyzeLog.set("expected", expected); // 写入期望。
        logChecks.add(analyzeLog); // 添加日志检查。

        if (testScope == CodeTestScope.TOOL_CHAIN) { // Tool 测试额外验证目标 Tool 日志。
            ObjectNode toolLog = objectMapper.createObjectNode(); // Tool 日志。
            toolLog.put("type", "TOOL_CALL_LOG"); // 类型。
            toolLog.put("description", "检查目标 AI Tool 的工具调用日志。"); // 描述。
            ArrayNode toolExpected = objectMapper.createArrayNode(); // 期望。
            toolExpected.add("tool_name = " + safe(firstNonBlank(text(request, "toolName"), result.path("aiToolInfo").path("toolName").asText("")))); // tool_name。
            toolExpected.add("arguments_json 只包含真实解析到的参数"); // 参数。
            toolExpected.add("result_json.success 与实际执行结果一致"); // 结果。
            toolLog.set("expected", toolExpected); // 写入。
            logChecks.add(toolLog); // 添加。
        }
        if (testScope == CodeTestScope.CONTROLLER_ENDPOINT) { // 接口测试额外验证 endpoint 参数。
            ObjectNode endpointLog = objectMapper.createObjectNode(); // endpoint 日志。
            endpointLog.put("type", "TOOL_CALL_LOG"); // 类型。
            endpointLog.put("description", "检查接口测试步骤的 endpoint 参数没有被当成 path。"); // 描述。
            ArrayNode endpointExpected = objectMapper.createArrayNode(); // 期望。
            endpointExpected.add("arguments_json.endpoint = " + safe(firstNonBlank(text(request, "endpoint"), text(result, "endpoint")))); // endpoint。
            endpointExpected.add("arguments_json.path 不应为接口路径"); // 不当 path。
            endpointLog.set("expected", endpointExpected); // 写入。
            logChecks.add(endpointLog); // 添加。
        }
    }

    private void fillRegressionChecks(CodeTestScope testScope, ArrayNode regressionChecks) { // 生成回归项。
        regressionChecks.add("readProjectFile：读取/打开/给我代码类请求仍走 readProjectFile，不被 TEST_STEPS 抢走。"); // 读取回归。
        regressionChecks.add("searchCode：在哪/搜索/定位类请求仍走 searchCode，并能刷新 recentProjectTarget。"); // 搜索回归。
        regressionChecks.add("listProjectTree：项目目录树和模块结构请求仍走 listProjectTree。"); // 目录树回归。
        regressionChecks.add("analyzeCode：STRUCTURE、CALL_CHAIN、CONTROLLER_SERVICE、TOOL_SERVICE、SSE_EVENT_CHAIN、EXPLANATION、RISK 仍可按 analysisType 分发。"); // 分析回归。
        regressionChecks.add("ToolRegistry：不出现 generateTestSteps、analyzeTestSteps 或旧专项 analyzeCallChain/analyzeControllerServiceChain/analyzeToolServiceChain/analyzeSseEventChain。"); // Tool 暴露回归。
        regressionChecks.add("安全边界：结果不返回服务器绝对路径、不修改数据库、不执行 SQL、不自动跑测试。"); // 安全回归。
        if (testScope == CodeTestScope.SSE_CHAIN) { // SSE 专项回归。
            regressionChecks.add("SSE：summary_result、tool_call、tool_result、done、error 等真实事件处理不被测试步骤生成影响。"); // SSE 回归。
        }
    }

    private void addTestCase(TestStepState state,
                             String title,
                             String type,
                             String priority,
                             String confidence,
                             ArrayNode steps,
                             ArrayNode expectedResult,
                             ArrayNode evidence,
                             String requestExampleOrRiskId) { // 添加一个测试用例。
        if (!state.accepts(type) || state.testCases.size() >= state.maxItems) { // 类型过滤或达到上限。
            return; // 不添加。
        }
        ObjectNode testCase = objectMapper.createObjectNode(); // 创建用例对象。
        testCase.put("id", "TC-" + String.format(Locale.ROOT, "%03d", state.testCases.size() + 1)); // 连续编号。
        testCase.put("title", title); // 标题。
        testCase.put("type", type); // 类型。
        testCase.put("priority", priority); // 优先级。
        testCase.put("confidence", confidence); // 置信度。
        testCase.set("steps", steps == null ? objectMapper.createArrayNode() : steps); // 操作步骤。
        if (state.includeExpectedResult) { // 按参数控制预期结果。
            testCase.set("expectedResult", expectedResult == null ? objectMapper.createArrayNode() : expectedResult); // 预期结果。
        }
        if (state.includeRequestExample && requestExampleOrRiskId != null && !requestExampleOrRiskId.isBlank()
                && !requestExampleOrRiskId.startsWith("RISK-")) { // 请求示例不写风险 ID。
            testCase.put("requestExample", requestExampleOrRiskId); // 请求示例。
        }
        testCase.set("evidence", evidence == null ? objectMapper.createArrayNode() : limitedEvidence(evidence)); // 证据。
        ArrayNode riskIds = objectMapper.createArrayNode(); // 关联风险。
        if (requestExampleOrRiskId != null && requestExampleOrRiskId.startsWith("RISK-")) { // 风险 ID。
            riskIds.add(requestExampleOrRiskId); // 写入关联风险。
        }
        testCase.set("relatedRiskIds", riskIds); // 关联风险列表。
        state.testCases.add(testCase); // 收集测试用例。
    }

    // ===================== 风险映射 =====================

    private ArrayNode stepsForRisk(String category, JsonNode risk) { // 按风险类型生成步骤。
        return switch (category) {
            case "PATH_TRAVERSAL", "SENSITIVE_FILE" -> steps("输入 ../、绝对路径和敏感文件名触发目标功能。",
                    "检查 ProjectPathGuard 或等价校验是否拒绝请求。",
                    "确认响应不包含服务器绝对路径和敏感配置内容。"); // 路径安全。
            case "PARAM_VALIDATION" -> steps("构造缺参、空值、非法类型和超长字段。",
                    "触发接口、方法或 Tool。",
                    "检查返回错误是否可读，后端日志是否能定位参数问题。"); // 参数校验。
            case "SSE_STREAM", "FRONTEND_BACKEND_CONTRACT" -> steps("模拟正常流式响应、后端异常和连接中断。",
                    "检查 done/error 事件和前端 loading 状态。",
                    "核对前后端事件名是否一致。"); // SSE。
            case "TOOL_CALLING" -> steps("触发 Tool 成功路径和失败路径。",
                    "检查 tool_call_log、arguments_json、result_json 和 finalAnswer。",
                    "确认不会泄漏 DSML/tool_calls 内部格式。"); // Tool Calling。
            case "DATABASE_WRITE" -> steps("触发涉及 Mapper/Repository 的业务路径。",
                    "检查参数校验、重复提交、失败回滚或幂等表现。",
                    "确认没有不完整写入或越权写入。"); // 数据库。
            default -> steps("根据风险描述执行对应功能路径：" + safe(risk.path("title").asText(category)),
                    "观察正常、异常和边界输入下的返回与日志。",
                    "将静态推测与真实运行结果比对确认。"); // 兜底。
        };
    }

    private ArrayNode expectedForRisk(String category) { // 按风险类型生成预期。
        return switch (category) {
            case "PATH_TRAVERSAL", "SENSITIVE_FILE" -> expected("路径穿越、绝对路径和敏感文件访问被拒绝。", "不返回服务器绝对路径或敏感内容。");
            case "PARAM_VALIDATION" -> expected("非法参数返回可读错误。", "服务端不抛出未处理异常。");
            case "SSE_STREAM", "FRONTEND_BACKEND_CONTRACT" -> expected("SSE 正常结束有 done，异常路径有 error 或清理逻辑。", "前端不会无限 loading。");
            case "TOOL_CALLING" -> expected("工具失败有结构化 result_json。", "finalAnswer 不泄漏内部工具协议。");
            case "DATABASE_WRITE" -> expected("数据库写入只发生在有效业务路径。", "异常路径不会产生脏数据。");
            default -> expected("测试结果能验证或排除该风险点。", "静态 LOW/MEDIUM 置信度风险需人工确认。");
        };
    }

    private String riskTestType(String category) { // 风险类型映射测试类型。
        return switch (category) {
            case "PATH_TRAVERSAL", "SENSITIVE_FILE", "PARAM_VALIDATION", "PERMISSION" -> "SECURITY"; // 安全。
            case "SSE_STREAM", "FRONTEND_BACKEND_CONTRACT" -> "SSE"; // SSE。
            case "TOOL_CALLING" -> "TOOL_CALLING"; // Tool。
            case "LOGGING" -> "LOG"; // 日志。
            default -> "REGRESSION"; // 其它回归。
        };
    }

    private String priorityFromRiskLevel(String level) { // 风险级别映射测试优先级。
        return switch (normalizeUpper(level)) {
            case "HIGH" -> "HIGH"; // 高。
            case "MEDIUM" -> "MEDIUM"; // 中。
            case "LOW", "INFO" -> "LOW"; // 低。
            default -> "MEDIUM"; // 兜底。
        };
    }

    // ===================== evidence 与通用 JSON 辅助 =====================

    private ArrayNode evidenceFromResult(JsonNode result, String evidenceType) { // 从结果生成目标证据。
        ArrayNode evidence = objectMapper.createArrayNode(); // 证据数组。
        ObjectNode item = objectMapper.createObjectNode(); // 证据对象。
        putIfText(item, "filePath", firstNonBlank(text(result, "path"), text(result, "matchedControllerPath"))); // 文件路径。
        putIfText(item, "className", firstNonBlank(text(result, "className"), result.path("classInfo").path("name").asText(""), result.path("aiToolInfo").path("className").asText(""))); // 类名。
        putIfText(item, "methodName", firstNonBlank(text(result, "methodName"), text(result, "entryMethod"), "execute")); // 方法名。
        putIfText(item, "endpoint", text(result, "endpoint")); // endpoint。
        putIfText(item, "toolName", firstNonBlank(text(result, "toolName"), result.path("aiToolInfo").path("toolName").asText(""))); // toolName。
        putIfText(item, "eventName", text(result, "eventName")); // eventName。
        putIfPositive(item, "lineNumber", firstPositive(result.path("classInfo").path("lineNumber").asInt(0), result.path("lineNumber").asInt(0))); // 行号。
        item.put("evidenceType", evidenceType); // 证据类型。
        addEvidence(evidence, item); // 添加。
        return evidence; // 返回证据。
    }

    private ArrayNode evidenceFromArray(JsonNode array, String defaultPath, String evidenceType) { // 从数组项生成证据。
        ArrayNode evidence = objectMapper.createArrayNode(); // 证据数组。
        if (!array.isArray()) { // 非数组。
            return evidence; // 返回空。
        }
        for (JsonNode item : array) { // 遍历数组。
            ObjectNode evidenceItem = objectMapper.createObjectNode(); // 证据对象。
            putIfText(evidenceItem, "filePath", firstNonBlank(text(item, "filePath"), text(item, "path"), text(item, "controllerPath"), defaultPath)); // 文件路径。
            putIfText(evidenceItem, "className", firstNonBlank(text(item, "className"), text(item, "controllerClass"), text(item, "targetType"), text(item, "serviceType"), text(item, "type"))); // 类名。
            putIfText(evidenceItem, "methodName", firstNonBlank(text(item, "methodName"), text(item, "toMethod"), text(item, "controllerMethod"))); // 方法名。
            putIfText(evidenceItem, "endpoint", firstNonBlank(text(item, "endpoint"), text(item, "path"), text(item, "fullPath"))); // endpoint。
            putIfText(evidenceItem, "httpMethod", text(item, "httpMethod")); // HTTP 方法。
            putIfText(evidenceItem, "eventName", text(item, "eventName")); // 事件名。
            putIfPositive(evidenceItem, "lineNumber", item.path("lineNumber").asInt(0)); // 行号。
            evidenceItem.put("evidenceType", evidenceType); // 类型。
            addEvidence(evidence, evidenceItem); // 添加并去重。
            if (evidence.size() >= MAX_EVIDENCE_ITEMS) { // 达到证据上限。
                break; // 停止。
            }
        }
        return evidence; // 返回证据。
    }

    private ArrayNode evidenceFromCalls(JsonNode result, String evidenceType) { // 合并调用类证据。
        ArrayNode evidence = objectMapper.createArrayNode(); // 证据数组。
        mergeInto(evidence, evidenceFromArray(result.path("internalCalls"), text(result, "path"), evidenceType)); // 内部调用。
        mergeInto(evidence, evidenceFromArray(result.path("externalCalls"), text(result, "path"), evidenceType)); // 外部调用。
        mergeInto(evidence, evidenceFromArray(result.path("utilityCalls"), text(result, "path"), evidenceType)); // 工具调用。
        mergeInto(evidence, evidenceFromArray(result.path("serviceCalls"), text(result, "path"), evidenceType)); // Service。
        return limitedEvidence(evidence); // 限制数量。
    }

    private ArrayNode evidenceFromEndpoints(JsonNode result) { // Controller endpoint 证据。
        return evidenceFromArray(result.path("endpoints"), firstNonBlank(text(result, "path"), text(result, "matchedControllerPath")), "SPRING_ENDPOINT"); // 返回 endpoint 证据。
    }

    private ArrayNode evidenceFromSse(JsonNode result) { // SSE 证据。
        ArrayNode evidence = objectMapper.createArrayNode(); // 证据数组。
        mergeInto(evidence, evidenceFromArray(result.path("backendSseEndpoints"), text(result, "path"), "SSE_ENDPOINT")); // 后端入口。
        mergeInto(evidence, evidenceFromArray(result.path("backendEventSenders"), text(result, "path"), "SSE_EVENT_SENDER")); // 后端发送。
        mergeInto(evidence, evidenceFromArray(result.path("frontendEventHandlers"), "", "SSE_FRONTEND_HANDLER")); // 前端处理。
        mergeInto(evidence, evidenceFromArray(result.path("frontendCalls"), "", "SSE_FRONTEND_CALL")); // 前端请求。
        return limitedEvidence(evidence); // 限制数量。
    }

    private ArrayNode evidenceFromRisks(JsonNode riskResult, String category) { // 从风险结果取证据。
        ArrayNode evidence = objectMapper.createArrayNode(); // 证据数组。
        JsonNode risks = riskResult.path("risks"); // 风险数组。
        if (!risks.isArray()) { // 没有风险数组。
            return evidence; // 返回空。
        }
        for (JsonNode risk : risks) { // 遍历风险。
            if (category.equalsIgnoreCase(risk.path("category").asText(""))) { // 命中类型。
                mergeInto(evidence, limitedEvidence(risk.path("evidence"))); // 合并证据。
            }
        }
        return limitedEvidence(evidence); // 返回证据。
    }

    private ArrayNode limitedEvidence(JsonNode source) { // 限制证据数量。
        ArrayNode target = objectMapper.createArrayNode(); // 新数组。
        if (source != null && source.isArray()) { // 是数组。
            for (JsonNode item : source) { // 遍历。
                if (target.size() >= MAX_EVIDENCE_ITEMS) { // 达到上限。
                    break; // 停止。
                }
                target.add(item.deepCopy()); // 复制证据。
            }
        }
        return target; // 返回。
    }

    private ArrayNode mergeEvidence(ArrayNode first, ArrayNode second) { // 合并两个证据数组。
        ArrayNode merged = objectMapper.createArrayNode(); // 新数组。
        mergeInto(merged, first); // 合并第一个。
        mergeInto(merged, second); // 合并第二个。
        return limitedEvidence(merged); // 限制数量。
    }

    private void mergeInto(ArrayNode target, JsonNode source) { // 合并数组并简单去重。
        if (target == null || source == null || !source.isArray()) { // 无效输入。
            return; // 结束。
        }
        for (JsonNode item : source) { // 遍历来源。
            if (target.size() >= MAX_EVIDENCE_ITEMS) { // 达到上限。
                return; // 结束。
            }
            String signature = item.toString(); // JSON 签名。
            boolean exists = false; // 是否已存在。
            for (JsonNode existing : target) { // 遍历已有。
                if (existing.toString().equals(signature)) { // 重复。
                    exists = true; // 标记。
                    break; // 结束。
                }
            }
            if (!exists) { // 未重复。
                target.add(item.deepCopy()); // 添加。
            }
        }
    }

    private void addEvidence(ArrayNode evidence, ObjectNode item) { // 添加证据并去重。
        if (evidence == null || item == null || item.size() == 0) { // 无效。
            return; // 结束。
        }
        for (JsonNode existing : evidence) { // 遍历已有证据。
            if (existing.toString().equals(item.toString())) { // 重复。
                return; // 跳过。
            }
        }
        evidence.add(item); // 添加证据。
    }

    // ===================== 目标、摘要和格式辅助 =====================

    private ObjectNode buildFailureResult(CodeTestScope testScope, CodeTestType testType, ObjectNode request, JsonNode primary) { // 构造失败 JSON。
        ObjectNode failure = objectMapper.createObjectNode(); // 失败对象。
        failure.put("type", RESULT_TYPE); // 内层 type。
        failure.put("success", false); // 标记失败。
        failure.put("testScope", testScope.name()); // 测试范围。
        failure.put("testType", testType.name()); // 测试类型。
        copyTargetFields(failure, request, primary); // 已知目标。
        failure.put("message", primary.path("message").asText("未找到真实匹配目标，无法生成测试步骤。")); // 失败原因。
        copyCandidateFields(failure, primary); // 真实候选。
        failure.set("warnings", objectMapper.createArrayNode()); // 空 warnings。
        return failure; // 返回。
    }

    private String buildSummary(CodeTestScope testScope, JsonNode result) { // 构造测试目标摘要。
        return switch (testScope) {
            case CONTROLLER_ENDPOINT -> "本测试用于验证接口 " + safe(firstNonBlank(text(result, "endpoint"), firstEndpointPath(result.path("endpoints")))) + " 的正常路径、参数边界、Service 调用和日志可追踪性。";
            case TOOL_CHAIN -> "本测试用于验证 AI Tool " + safe(firstNonBlank(text(result, "toolName"), result.path("aiToolInfo").path("toolName").asText(""))) + " 的路由、参数、依赖组件、安全边界和 tool_call_log。";
            case SSE_CHAIN -> "本测试用于验证 SSE 链路的前端发起、后端事件发送、前端接收、done/error 和日志可追踪性。";
            case METHOD, CALL_CHAIN -> "本测试用于验证入口方法或调用链的正常路径、依赖调用、异常路径和回归表现。";
            case CLASS, AUTO -> "本测试用于验证类/文件结构、关键方法、接口或 Tool 信息对应的功能路径与回归边界。";
        };
    }

    private void copyTargetFields(ObjectNode target, ObjectNode request, JsonNode result) { // 复制轻量目标字段。
        putIfText(target, "path", firstNonBlank(text(request, "path"), text(result, "path"), text(result, "matchedControllerPath"))); // path。
        putIfText(target, "fileName", firstNonBlank(text(result, "fileName"), fileNameOf(text(target, "path")))); // fileName。
        putIfText(target, "className", firstNonBlank(text(request, "className"), text(result, "className"), result.path("classInfo").path("name").asText(""), result.path("aiToolInfo").path("className").asText(""))); // className。
        putIfText(target, "methodName", firstNonBlank(text(request, "methodName"), text(result, "methodName"), text(result, "entryMethod"))); // methodName。
        putIfText(target, "endpoint", firstNonBlank(text(request, "endpoint"), text(result, "endpoint"), firstEndpointPath(result.path("endpoints")))); // endpoint。
        putIfText(target, "toolName", firstNonBlank(text(request, "toolName"), text(result, "toolName"), result.path("aiToolInfo").path("toolName").asText(""))); // toolName。
        putIfText(target, "eventName", firstNonBlank(text(request, "eventName"), text(result, "eventName"), firstEventName(result))); // eventName。
        putIfText(target, "frontendKeyword", firstNonBlank(text(request, "frontendKeyword"), text(result, "frontendKeyword"))); // frontendKeyword。
    }

    private void copyCandidateFields(ObjectNode failure, JsonNode primary) { // 复制真实候选。
        copyArrayIfPresent(failure, primary, "candidates"); // 普通候选。
        copyArrayIfPresent(failure, primary, "candidateEndpoints"); // endpoint 候选。
        copyArrayIfPresent(failure, primary, "candidateEvents"); // SSE 候选。
        copyArrayIfPresent(failure, primary, "candidateTools"); // Tool 候选。
    }

    private void copyArrayIfPresent(ObjectNode target, JsonNode source, String fieldName) { // 复制数组字段。
        if (source != null && source.path(fieldName).isArray()) { // 字段是数组。
            target.set(fieldName, source.path(fieldName)); // 写入。
        }
    }

    private void copyWarnings(JsonNode source, ArrayNode warnings) { // 复制 warnings。
        JsonNode sourceWarnings = source == null ? null : source.path("warnings"); // 来源 warnings。
        if (sourceWarnings != null && sourceWarnings.isArray()) { // 是数组。
            for (JsonNode warning : sourceWarnings) { // 遍历。
                if (warning.isTextual()) { // 只复制文本。
                    warnings.add(warning.asText()); // 写入。
                }
            }
        }
    }

    private ArrayNode steps(String... values) { // 构造步骤数组。
        ArrayNode array = objectMapper.createArrayNode(); // 新数组。
        if (values != null) { // 有值。
            for (String value : values) { // 遍历。
                if (value != null && !value.isBlank()) { // 非空。
                    array.add(value); // 添加。
                }
            }
        }
        return array; // 返回数组。
    }

    private ArrayNode expected(String... values) { // 构造预期结果数组。
        return steps(values); // 复用步骤数组构造。
    }

    private String requestExampleForEndpoint(JsonNode result) { // 构造接口请求示例。
        String endpoint = firstNonBlank(text(result, "endpoint"), text(result, "path"), text(result, "fullPath"),
                firstEndpointPath(result.path("endpoints")), firstEndpointPath(result.path("backendSseEndpoints"))); // endpoint。
        String method = firstNonBlank(text(result, "httpMethod"), firstHttpMethod(result.path("endpoints"))); // HTTP 方法。
        if (method.isBlank() || "-".equals(method)) { // 无方法。
            method = "POST"; // 常见聊天接口默认 POST，但只是示例。
        }
        return endpoint.isBlank() ? "" : method + " " + endpoint; // 返回示例。
    }

    private String requestExampleForTool(String toolName) { // 构造 Tool 调用示例。
        return toolName == null || toolName.isBlank() ? "" : "聊天输入触发 Tool：" + toolName; // 示例。
    }

    private JsonNode firstEndpoint(JsonNode result) { // 读取第一条 spring endpoint。
        JsonNode endpoints = result.path("springEndpoints"); // 结构分析 endpoint。
        if (endpoints.isArray() && endpoints.size() > 0) { // 有 endpoint。
            return endpoints.get(0); // 返回第一项。
        }
        return objectMapper.createObjectNode(); // 空对象。
    }

    private String firstEndpointPath(JsonNode endpoints) { // 从 endpoint 数组读路径。
        if (endpoints != null && endpoints.isArray() && endpoints.size() > 0) { // 有 endpoint。
            JsonNode first = endpoints.get(0); // 第一项。
            return firstNonBlank(text(first, "path"), text(first, "endpoint"), text(first, "fullPath")); // 返回路径。
        }
        return ""; // 无。
    }

    private String firstHttpMethod(JsonNode endpoints) { // 从 endpoint 数组读 HTTP 方法。
        if (endpoints != null && endpoints.isArray() && endpoints.size() > 0) { // 有 endpoint。
            return endpoints.get(0).path("httpMethod").asText("-"); // 返回方法。
        }
        return "-"; // 无。
    }

    private String firstEventName(JsonNode result) { // 从 SSE 结果读第一个事件名。
        String eventName = firstEventNameFromArray(result.path("backendEventSenders")); // 后端发送优先。
        if (!eventName.isBlank()) { // 有后端事件。
            return eventName; // 返回。
        }
        return firstEventNameFromArray(result.path("frontendEventHandlers")); // 再看前端处理。
    }

    private String firstEventNameFromArray(JsonNode array) { // 从数组读事件名。
        if (array != null && array.isArray()) { // 是数组。
            for (JsonNode item : array) { // 遍历。
                String eventName = item.path("eventName").asText(""); // 事件名。
                if (!eventName.isBlank()) { // 非空。
                    return eventName; // 返回。
                }
            }
        }
        return ""; // 无。
    }

    private boolean hasGuardEvidence(JsonNode result) { // 判断是否有路径 Guard 证据。
        return result.path("guardCalls").size() > 0 || hasDependencyKeyword(result.path("dependencies"), "guard"); // Guard 调用或依赖。
    }

    private boolean hasDependencyKeyword(JsonNode dependencies, String keyword) { // 判断依赖类型是否包含关键词。
        if (!dependencies.isArray()) { // 非数组。
            return false; // 返回 false。
        }
        for (JsonNode dependency : dependencies) { // 遍历依赖。
            String type = firstNonBlank(text(dependency, "type"), text(dependency, "kind")); // 类型。
            if (type.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) { // 命中。
                return true; // 返回 true。
            }
        }
        return false; // 未命中。
    }

    private boolean hasRiskCategory(JsonNode riskResult, String category) { // 判断风险结果是否含指定类型。
        JsonNode risks = riskResult.path("risks"); // 风险数组。
        if (!risks.isArray()) { // 无风险。
            return false; // false。
        }
        for (JsonNode risk : risks) { // 遍历。
            if (category.equalsIgnoreCase(risk.path("category").asText(""))) { // 命中。
                return true; // true。
            }
        }
        return false; // 未命中。
    }

    private ObjectNode copyArguments(JsonNode arguments) { // 复制工具参数。
        return arguments instanceof ObjectNode objectNode ? objectNode.deepCopy() : objectMapper.createObjectNode(); // 非 object 入参按空对象。
    }

    private JsonNode readJson(String json) throws Exception { // 安全解析 Analyzer 结果。
        return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json); // 空结果按空对象。
    }

    private boolean resolveBoolean(ObjectNode request, String fieldName, boolean defaultValue) { // 解析布尔参数。
        if (request == null || !request.hasNonNull(fieldName)) { // 缺失。
            return defaultValue; // 默认。
        }
        return request.path(fieldName).asBoolean(defaultValue); // 返回值。
    }

    private int resolveMaxItems(ObjectNode request) { // 解析 maxItems。
        int maxItems = getOptionalInt(request, "maxItems", DEFAULT_MAX_ITEMS); // 默认 100。
        if (maxItems <= 0) { // 非法。
            return DEFAULT_MAX_ITEMS; // 默认。
        }
        return Math.min(maxItems, MAX_ALLOWED_ITEMS); // 最大 300。
    }

    private boolean hasText(ObjectNode node, String fieldName) { // 判断字段非空。
        return node != null && node.hasNonNull(fieldName) && !node.path(fieldName).asText("").isBlank(); // 非空白。
    }

    private String firstText(ObjectNode node, String... fields) { // 从请求读取第一个非空字段。
        if (fields == null) { // 无字段。
            return ""; // 空。
        }
        for (String field : fields) { // 遍历字段。
            String value = node == null ? "" : node.path(field).asText(""); // 读取。
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
            return ""; // 返回空。
        }
        for (String value : values) { // 遍历。
            if (value != null && !value.isBlank()) { // 命中。
                return value.trim(); // 返回。
            }
        }
        return ""; // 无。
    }

    private int firstPositive(int... values) { // 返回第一个正整数。
        if (values == null) { // 无值。
            return 0; // 0。
        }
        for (int value : values) { // 遍历。
            if (value > 0) { // 正数。
                return value; // 返回。
            }
        }
        return 0; // 无。
    }

    private boolean looksLikeToolTarget(String pathOrClass) { // 判断目标是否像 AI Tool。
        if (pathOrClass == null || pathOrClass.isBlank()) { // 空值。
            return false; // false。
        }
        String lower = pathOrClass.replace('\\', '/').toLowerCase(Locale.ROOT); // 统一路径。
        return lower.endsWith("tool.java") || lower.endsWith("tool") || lower.contains("/tool/"); // Tool 文件/类/目录。
    }

    private String normalizeUpper(String value) { // 归一化大写。
        return value == null ? "" : value.trim().replace('-', '_').toUpperCase(Locale.ROOT); // 大写。
    }

    private String safe(String value) { // 安全展示短文本。
        if (value == null || value.isBlank()) { // 空。
            return "-"; // 占位。
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim(); // 去换行。
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 180) + "..."; // 控制长度。
    }

    private String fileNameOf(String path) { // 从路径提取文件名。
        if (path == null || path.isBlank()) { // 空。
            return ""; // 空。
        }
        String normalized = path.replace('\\', '/'); // 统一分隔符。
        int index = normalized.lastIndexOf('/'); // 最后分隔符。
        return index >= 0 ? normalized.substring(index + 1) : normalized; // 返回文件名。
    }

    private void putIfText(ObjectNode node, String fieldName, String value) { // 非空文本写入 JSON。
        if (node != null && value != null && !value.isBlank()) { // 有效。
            node.put(fieldName, value); // 写入。
        }
    }

    private void putIfPositive(ObjectNode node, String fieldName, int value) { // 正整数写入 JSON。
        if (node != null && value > 0) { // 有效行号。
            node.put(fieldName, value); // 写入。
        }
    }

    /**
     * 测试范围：决定复用哪个底层 Analyzer，以及测试步骤围绕类、方法、接口、Tool、调用链还是 SSE 链路展开。
     */
    private enum CodeTestScope { // 测试范围枚举。
        AUTO, // 根据参数自动判断。
        CLASS, // 单个类/文件测试。
        METHOD, // 单个方法测试。
        CONTROLLER_ENDPOINT, // Controller 接口测试。
        TOOL_CHAIN, // AI Tool 执行链路测试。
        CALL_CHAIN, // 普通调用链测试。
        SSE_CHAIN; // 前后端 SSE 链路测试。

        private static CodeTestScope from(String rawValue) { // 解析 testScope。
            if (rawValue == null || rawValue.isBlank()) { // 未传。
                return AUTO; // 默认 AUTO。
            }
            try {
                return CodeTestScope.valueOf(rawValue.trim().replace('-', '_').toUpperCase(Locale.ROOT)); // 解析枚举。
            } catch (IllegalArgumentException ex) {
                return AUTO; // 非法值兜底。
            }
        }
    }

    /**
     * 测试类型：用于过滤或组织手动、接口、Tool Calling、SSE、回归、安全和日志测试步骤。
     */
    private enum CodeTestType { // 测试类型枚举。
        ALL, // 全部类型。
        MANUAL, // 手动验证。
        API, // 接口测试。
        TOOL_CALLING, // Tool Calling 测试。
        SSE, // SSE 流式测试。
        REGRESSION, // 回归测试。
        SECURITY, // 安全测试。
        LOG; // 日志验证。

        private static CodeTestType from(String rawValue) { // 解析 testType。
            if (rawValue == null || rawValue.isBlank()) { // 未传。
                return ALL; // 默认 ALL。
            }
            try {
                return CodeTestType.valueOf(rawValue.trim().replace('-', '_').toUpperCase(Locale.ROOT)); // 解析枚举。
            } catch (IllegalArgumentException ex) {
                return ALL; // 非法值兜底。
            }
        }
    }

    /**
     * 测试步骤收集状态：保存过滤条件、输出开关和用例列表。
     */
    private final class TestStepState { // 测试用例收集器。
        private final CodeTestType testType; // 测试类型过滤。
        private final boolean includeExpectedResult; // 是否输出预期结果。
        private final boolean includeRequestExample; // 是否输出请求示例。
        private final int maxItems; // 用例上限。
        private final ArrayNode testCases = objectMapper.createArrayNode(); // 测试用例数组。

        private TestStepState(CodeTestType testType, boolean includeExpectedResult,
                              boolean includeRequestExample, int maxItems) { // 构造状态。
            this.testType = testType == null ? CodeTestType.ALL : testType; // 默认 ALL。
            this.includeExpectedResult = includeExpectedResult; // 保存开关。
            this.includeRequestExample = includeRequestExample; // 保存开关。
            this.maxItems = maxItems <= 0 ? DEFAULT_MAX_ITEMS : maxItems; // 保存上限。
        }

        private boolean accepts(String type) { // 判断测试类型是否通过过滤。
            return testType == CodeTestType.ALL || testType.name().equalsIgnoreCase(type); // ALL 或同类型。
        }
    }
}

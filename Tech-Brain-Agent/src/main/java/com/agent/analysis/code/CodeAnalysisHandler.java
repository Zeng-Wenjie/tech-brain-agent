package com.agent.analysis.code; // 项目代码内部分析器包。

import com.agent.toolcalling.project.analysis.CodeAnalysisType; // analyzeCode 统一分析类型。
import com.fasterxml.jackson.databind.JsonNode; // 统一接收 analyzeCode 分发后的参数。

/**
 * analyzeCode 内部分析器接口。
 *
 * <p>适用场景：P5.5.5 架构收敛后，CallChainAnalyzer、ControllerServiceChainAnalyzer、
 * ToolServiceChainAnalyzer、SseEventChainAnalyzer 和 CodeStructureAnalyzer 不再作为对外 AI Tool 注册，
 * 而是实现本接口，由 AnalyzeCodeTool 根据 analysisType 做内部策略分发。</p>
 *
 * <p>调用链：AnalyzeCodeTool.execute(arguments) -> 解析 CodeAnalysisType -> 根据 analysisType 获取
 * CodeAnalysisHandler -> analyze(arguments) 执行原专项静态分析能力 -> AnalyzeCodeTool 包装统一 code_analysis 外层结果。</p>
 *
 * <p>边界说明：本接口不继承 AiTool，不会被 ToolRegistry 暴露；实现类只做只读静态分析，不修改项目文件、
 * 不访问 workspace 外路径、不接入 RAG/Milvus/向量化，也不负责 tool_call_log 持久化。</p>
 */
public interface CodeAnalysisHandler { // analyzeCode 内部策略接口。

    CodeAnalysisType analysisType(); // 返回当前分析器负责的 analysisType。

    String analyze(JsonNode arguments); // 执行内部分析并返回原能力的 JSON 字符串。
}

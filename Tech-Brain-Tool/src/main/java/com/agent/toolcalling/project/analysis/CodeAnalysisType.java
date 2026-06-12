package com.agent.toolcalling.project.analysis; // 项目代码分析类型公共枚举包。

import java.util.Locale; // 统一大小写解析 analysisType。

/**
 * 项目代码分析类型枚举。
 *
 * <p>适用场景：P5.5.5 之后，外部只暴露 analyzeCode 一个 AI Tool，ToolCallingChatServiceImpl 在路由阶段把
 * 用户意图转换为 analysisType，AnalyzeCodeTool 再按本枚举分发到内部 Analyzer。</p>
 *
 * <p>调用链：ToolCallingChatServiceImpl 构造 analyzeCode.arguments.analysisType -> AnalyzeCodeTool 解析本枚举
 * -> CodeStructureAnalyzer / CallChainAnalyzer / ControllerServiceChainAnalyzer / ToolServiceChainAnalyzer /
 * SseEventChainAnalyzer / CodeExplanationGenerator 执行真实静态分析或说明生成 -> analyzeCode 返回统一 code_analysis 外层 JSON。</p>
 *
 * <p>边界说明：本枚举描述 P5.1 到 P5.7 已完成的分析类型（含 RISK 风险点说明）；P5.8 测试步骤和 P5.9
 * 开发日志保存不在当前枚举范围内，避免提前暴露未实现能力。</p>
 */
public enum CodeAnalysisType { // analyzeCode 统一分发使用的分析类型。
    AUTO, // 自动根据参数和路由意图推断分析类型。
    STRUCTURE, // 单文件结构分析。
    CALL_CHAIN, // 普通类/方法轻量调用链分析。
    CONTROLLER_SERVICE, // Spring Controller 到 Service 专项链路分析。
    TOOL_SERVICE, // AI Tool 到业务 Service / Guard / Registry / Mapper / Repository 专项链路分析。
    SSE_EVENT_CHAIN, // 前后端 SSE 事件链路分析。
    EXPLANATION, // 基于已有真实分析结果生成开发者/面试/新手代码说明。
    RISK; // P5.7 基于已有真实分析结果生成面向开发者的代码风险点说明。

    public static CodeAnalysisType from(String rawType) { // 从工具参数字符串解析枚举。
        if (rawType == null || rawType.isBlank()) { // analysisType 未传时默认 AUTO。
            return AUTO; // 返回自动模式。
        }
        String normalizedType = rawType.trim().replace('-', '_').toUpperCase(Locale.ROOT); // 兼容小写、短横线和下划线。
        for (CodeAnalysisType type : values()) { // 遍历枚举值。
            if (type.name().equals(normalizedType)) { // 命中合法枚举。
                return type; // 返回解析结果。
            }
        }
        return AUTO; // 非法值兜底为 AUTO，避免模型大小写或格式轻微偏差导致失败。
    }
}

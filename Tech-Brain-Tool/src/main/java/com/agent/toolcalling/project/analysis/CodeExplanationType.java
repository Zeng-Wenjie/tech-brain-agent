package com.agent.toolcalling.project.analysis; // 项目代码说明类型公共枚举包。

import java.util.Locale; // 统一大小写解析 explanationType。

/**
 * 代码说明生成类型枚举。
 *
 * <p>适用场景：P5.6 只增强 analyzeCode 一个对外 Tool，当 analysisType=EXPLANATION 时，AnalyzeCodeTool
 * 会把 explanationType 传给内部 CodeExplanationGenerator，由它决定复用结构分析、调用链分析、Controller→Service、
 * Tool→Service 或 SSE 事件链路分析结果来生成说明。</p>
 *
 * <p>调用链：ToolCallingChatServiceImpl 识别“说明/解释/面试话术”等意图并构造 analyzeCode arguments
 * -> AnalyzeCodeTool 分发到 CodeExplanationGenerator -> CodeExplanationGenerator 解析本枚举并复用已有内部 Analyzer
 * -> 返回 code_explanation 结果给 finalAnswer 和 tool_call_log。</p>
 *
 * <p>边界说明：本枚举只描述说明生成视角，不代表新的对外 AI Tool；它不会被 ToolRegistry 注册，也不生成风险分析、
 * 测试步骤、修改建议或 patch。</p>
 */
public enum CodeExplanationType { // CodeExplanationGenerator 内部说明类型。
    AUTO, // 根据参数和目标自动判断说明类型。
    CLASS, // 单个类或文件职责说明。
    METHOD, // 单个方法执行流程说明。
    CONTROLLER_ENDPOINT, // Controller 接口后端流程说明。
    TOOL_CHAIN, // AI Tool 执行链路和依赖组件说明。
    CALL_CHAIN, // 普通调用链说明。
    SSE_CHAIN; // 前后端 SSE 事件链路说明。

    public static CodeExplanationType from(String rawType) { // 从工具参数字符串解析说明类型。
        if (rawType == null || rawType.isBlank()) { // 未传时默认 AUTO。
            return AUTO; // 返回自动判断。
        }
        String normalizedType = rawType.trim().replace('-', '_').toUpperCase(Locale.ROOT); // 兼容小写、短横线和下划线。
        for (CodeExplanationType type : values()) { // 遍历合法枚举值。
            if (type.name().equals(normalizedType)) { // 命中合法值。
                return type; // 返回解析结果。
            }
        }
        return AUTO; // 非法值兜底为 AUTO，避免模型参数轻微偏差导致失败。
    }
}

package com.agent.toolcalling.devlog;

/**
 * 开发行为日志保存结果（P5.9 跨模块回传对象）。
 *
 * <p>适用场景：作为 {@link DevActionLogRecorder} “保存上一条分析结果”能力的返回值，承载本次保存是否找到可保存的
 * 代码分析结果、是否保存成功、生成的开发日志 ID 以及失败原因，供 Tech-Brain-Tool 路由层生成 finalAnswer 文案。</p>
 *
 * <p>调用链：ToolCallingChatServiceImpl 命中“保存到开发日志”意图且没有新目标时调用
 * DevActionLogRecorder.saveLastCodeAnalysis -> Agent 模块实现读取最近一条 analyzeCode 的 tool_call_log 并写入
 * dev_action_log -> 返回本对象 -> 路由层据此回答“已保存，日志 ID：xxx”或“当前没有可保存的代码分析结果”。</p>
 *
 * <p>边界说明：本对象位于 Tech-Brain-Tool 模块，只承载保存结论，不依赖 Agent Service、Mapper、数据库实现。</p>
 */
public final class DevActionLogSaveResult { // 开发行为日志保存结果（不可变）。

    private final boolean analysisFound; // 是否找到可保存的上一条代码分析结果。
    private final boolean saved; // 是否成功写入 dev_action_log。
    private final Long devLogId; // 成功时生成的开发日志 ID，可空。
    private final String errorMessage; // 保存失败原因，可空。

    private DevActionLogSaveResult(boolean analysisFound, boolean saved, Long devLogId, String errorMessage) { // 私有构造，统一通过静态工厂创建。
        this.analysisFound = analysisFound; // 保存是否找到可保存结果。
        this.saved = saved; // 保存是否落库成功。
        this.devLogId = devLogId; // 保存生成的日志 ID。
        this.errorMessage = errorMessage; // 保存失败原因。
    }

    public static DevActionLogSaveResult notFound() { // 没有可保存的代码分析结果。
        return new DevActionLogSaveResult(false, false, null, null); // analysisFound=false。
    }

    public static DevActionLogSaveResult success(Long devLogId) { // 保存成功。
        return new DevActionLogSaveResult(true, true, devLogId, null); // saved=true 并携带日志 ID。
    }

    public static DevActionLogSaveResult failed(String errorMessage) { // 找到结果但保存失败。
        return new DevActionLogSaveResult(true, false, null, errorMessage); // saved=false 并携带失败原因。
    }

    public boolean isAnalysisFound() { // 是否找到可保存的上一条分析结果。
        return analysisFound; // 返回查找结论。
    }

    public boolean isSaved() { // 是否保存成功。
        return saved; // 返回保存结论。
    }

    public Long getDevLogId() { // 获取生成的开发日志 ID。
        return devLogId; // 返回日志 ID。
    }

    public String getErrorMessage() { // 获取保存失败原因。
        return errorMessage; // 返回失败原因。
    }
}

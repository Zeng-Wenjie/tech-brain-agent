package com.agent.toolcalling.devlog;

/**
 * 开发行为日志保存结果。
 *
 * <p>适用场景：承载一次 dev_action_log 保存是否成功、生成的开发日志 ID 以及失败原因。</p>
 *
 * <p>边界说明：本对象位于 Tech-Brain-Tool 模块，只承载保存结论，不依赖 Agent Service、Mapper、数据库实现。</p>
 */
public final class DevActionLogSaveResult { // 开发行为日志保存结果（不可变）。

    private final boolean sourceFound; // 是否有可保存的来源数据。
    private final boolean saved; // 是否成功写入 dev_action_log。
    private final Long devLogId; // 成功时生成的开发日志 ID，可空。
    private final String errorMessage; // 保存失败原因，可空。

    private DevActionLogSaveResult(boolean sourceFound, boolean saved, Long devLogId, String errorMessage) { // 私有构造，统一通过静态工厂创建。
        this.sourceFound = sourceFound; // 保存是否找到可保存来源。
        this.saved = saved; // 保存是否落库成功。
        this.devLogId = devLogId; // 保存生成的日志 ID。
        this.errorMessage = errorMessage; // 保存失败原因。
    }

    public static DevActionLogSaveResult notFound() { // 没有可保存的来源数据。
        return new DevActionLogSaveResult(false, false, null, null); // sourceFound=false。
    }

    public static DevActionLogSaveResult success(Long devLogId) { // 保存成功。
        return new DevActionLogSaveResult(true, true, devLogId, null); // saved=true 并携带日志 ID。
    }

    public static DevActionLogSaveResult failed(String errorMessage) { // 找到来源但保存失败。
        return new DevActionLogSaveResult(true, false, null, errorMessage); // saved=false 并携带失败原因。
    }

    public boolean isAnalysisFound() { // 兼容旧调用名：是否找到可保存来源。
        return sourceFound; // 返回查找结论。
    }

    public boolean isSourceFound() { // 是否找到可保存来源。
        return sourceFound; // 返回查找结论。
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

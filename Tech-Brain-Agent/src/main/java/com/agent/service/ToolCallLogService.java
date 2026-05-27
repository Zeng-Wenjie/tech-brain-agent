package com.agent.service;

import com.agent.entity.ToolCallLog;
import com.agent.entity.dto.ToolCallLogCreateRequest;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * Tool Calling 调用日志服务接口。
 *
 * <p>适用场景：为后续 Tool Calling 链路提供统一的日志创建、成功更新、失败更新和最终回答回填入口。</p>
 * <p>调用链：后续 Tool Calling 编排代码 -> ToolCallLogService -> ToolCallLogServiceImpl
 * -> ToolCallLogMapper -> tool_call_log 表。</p>
 * <p>边界说明：本接口只定义基础持久化能力，不接入 ToolCallingChatServiceImpl，
 * 不修改聊天主链路，不修改前端，不执行建表 SQL。</p>
 */
public interface ToolCallLogService extends IService<ToolCallLog> { // 继承 MyBatis-Plus 通用 Service 能力。

    Long createRunningLog(ToolCallLogCreateRequest request); // 创建 success=0 的运行中工具调用日志。

    void markSuccess(Long id, String resultJson, Long durationMs); // 按日志 ID 标记工具调用成功。

    void markFailed(Long id, String errorMessage, Long durationMs); // 按日志 ID 标记工具调用失败。

    void updateFinalAnswerByTraceId(String traceId, String finalAnswer); // 按 traceId 回填同一轮所有工具调用的最终回答。
}

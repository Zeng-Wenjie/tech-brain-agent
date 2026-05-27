package com.agent.service.impl;

import com.agent.entity.ToolCallLog;
import com.agent.entity.dto.ToolCallLogCreateRequest;
import com.agent.mapper.ToolCallLogMapper;
import com.agent.service.ToolCallLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tool Calling 调用日志服务实现。
 *
 * <p>适用场景：提供 tool_call_log 表的基础写入和更新能力，用于记录工具调用从创建、执行完成
 * 到最终回答回填的完整生命周期。</p>
 * <p>调用链：后续 Tool Calling 编排链路会先调用 createRunningLog 创建运行中日志，
 * 工具执行成功时调用 markSuccess，工具执行失败时调用 markFailed，最终回答完成后调用
 * updateFinalAnswerByTraceId 回填同 traceId 下的全部记录。</p>
 * <p>边界说明：本实现只访问 tool_call_log 表，不创建表，不执行建表 SQL，不修改数据库结构，
 * 接入主链路时只由 ChatMessageServiceImpl 提供回调适配，不改变 ragSearch 和 summarizeArticle 的业务逻辑。</p>
 */
@Slf4j // 使用 SLF4J 记录必要状态，不打印完整 argumentsJson、resultJson、finalAnswer。
@Service // 注册为 Spring Bean，供后续 Tool Calling 链路按接口注入。
public class ToolCallLogServiceImpl extends ServiceImpl<ToolCallLogMapper, ToolCallLog> implements ToolCallLogService { // Tool 调用日志服务实现。

    private static final String UNKNOWN_TOOL_NAME = "UNKNOWN"; // 工具名称缺失时的兜底值。
    private static final int TRACE_ID_MAX_LENGTH = 64; // trace_id 字段最大长度。
    private static final int TOOL_NAME_MAX_LENGTH = 100; // tool_name 字段最大长度。
    private static final int TOOL_TYPE_MAX_LENGTH = 50; // tool_type 字段最大长度。
    private static final int CALL_SOURCE_MAX_LENGTH = 50; // call_source 字段最大长度。
    private static final int ROUTE_REASON_MAX_LENGTH = 255; // route_reason 字段最大长度。
    private static final int USER_MESSAGE_MAX_LENGTH = 2000; // user_message 日志入库最大长度。
    private static final int ARGUMENTS_JSON_MAX_LENGTH = 10000; // arguments_json 日志入库最大长度。
    private static final int RESULT_JSON_MAX_LENGTH = 50000; // result_json 日志入库最大长度。
    private static final int FINAL_ANSWER_MAX_LENGTH = 50000; // final_answer 日志入库最大长度。
    private static final int ERROR_MESSAGE_MAX_LENGTH = 5000; // error_message 日志入库最大长度。

    @Override // 创建运行中的工具调用日志。
    public Long createRunningLog(ToolCallLogCreateRequest request) {
        if (request == null) { // 创建日志必须有请求对象。
            throw new IllegalArgumentException("ToolCallLogCreateRequest cannot be null"); // 参数非法时直接抛出，避免写入脏日志。
        }

        LocalDateTime now = LocalDateTime.now(); // 创建和更新时间使用同一个时间点。
        String traceId = resolveTraceId(request.getTraceId()); // traceId 为空时自动生成 UUID。
        String toolName = resolveToolName(request.getToolName()); // toolName 为空时使用 UNKNOWN。
        ToolCallLog logRecord = new ToolCallLog(); // 构造 tool_call_log 新记录。
        logRecord.setTraceId(traceId); // 写入追踪 ID。
        logRecord.setConversationId(request.getConversationId()); // 写入会话 ID。
        logRecord.setUserId(request.getUserId()); // 写入用户 ID。
        logRecord.setUserMessage(safeText(request.getUserMessage(), USER_MESSAGE_MAX_LENGTH)); // 控制用户消息长度。
        logRecord.setToolName(toolName); // 写入兜底后的工具名称。
        logRecord.setToolType(safeText(request.getToolType(), TOOL_TYPE_MAX_LENGTH)); // 控制工具类型长度。
        logRecord.setCallSource(safeText(request.getCallSource(), CALL_SOURCE_MAX_LENGTH)); // 控制调用来源长度。
        logRecord.setRouteReason(safeText(request.getRouteReason(), ROUTE_REASON_MAX_LENGTH)); // 控制路由原因长度。
        logRecord.setArgumentsJson(safeText(request.getArgumentsJson(), ARGUMENTS_JSON_MAX_LENGTH)); // 控制参数 JSON 长度。
        logRecord.setSuccess(0); // 初始状态为运行中或未成功。
        logRecord.setCreateTime(now); // 手动设置创建时间，避免依赖自动填充配置。
        logRecord.setUpdateTime(now); // 手动设置更新时间，避免依赖自动填充配置。
        save(logRecord); // 使用 MyBatis-Plus 保存记录并回填自增 ID。
        log.info("[ToolCallLog] create running log, traceId: {}, toolName: {}", traceId, toolName); // 只打印 traceId 和工具名。
        return logRecord.getId(); // 返回自增主键，供后续成功或失败更新使用。
    }

    @Override // 标记工具调用成功。
    public void markSuccess(Long id, String resultJson, Long durationMs) {
        if (id == null) { // 日志 ID 为空时无法定位记录。
            log.warn("[ToolCallLog] skip mark success, id is null"); // 只记录安全提示。
            return; // 直接返回，不抛异常影响主链路。
        }

        ToolCallLog logRecord = getById(id); // 根据主键查询已有日志。
        if (logRecord == null) { // 记录不存在时不执行更新。
            log.warn("[ToolCallLog] skip mark success, log not found, id: {}", id); // 只打印 ID。
            return; // 直接返回，避免空指针。
        }

        logRecord.setResultJson(safeText(resultJson, RESULT_JSON_MAX_LENGTH)); // 控制结果 JSON 长度。
        logRecord.setDurationMs(durationMs); // 写入工具调用耗时。
        logRecord.setSuccess(1); // 标记成功。
        logRecord.setErrorMessage(null); // 成功时清空失败原因。
        logRecord.setUpdateTime(LocalDateTime.now()); // 刷新更新时间。
        updateById(logRecord); // 根据主键更新日志记录。
        log.info("[ToolCallLog] mark success, id: {}, durationMs: {}", id, durationMs); // 不打印完整 resultJson。
    }

    @Override // 标记工具调用失败。
    public void markFailed(Long id, String errorMessage, Long durationMs) {
        if (id == null) { // 日志 ID 为空时无法定位记录。
            log.warn("[ToolCallLog] skip mark failed, id is null"); // 只记录安全提示。
            return; // 直接返回，不抛异常影响主链路。
        }

        ToolCallLog logRecord = getById(id); // 根据主键查询已有日志。
        if (logRecord == null) { // 记录不存在时不执行更新。
            log.warn("[ToolCallLog] skip mark failed, log not found, id: {}", id); // 只打印 ID。
            return; // 直接返回，避免空指针。
        }

        logRecord.setSuccess(0); // 标记失败。
        logRecord.setErrorMessage(safeText(errorMessage, ERROR_MESSAGE_MAX_LENGTH)); // 控制失败原因长度。
        logRecord.setDurationMs(durationMs); // 写入工具调用耗时。
        logRecord.setUpdateTime(LocalDateTime.now()); // 刷新更新时间。
        updateById(logRecord); // 根据主键更新日志记录。
        log.info("[ToolCallLog] mark failed, id: {}, durationMs: {}", id, durationMs); // 不打印完整错误堆栈。
    }

    @Override // 按 traceId 回填同一轮全部工具调用记录的最终回答。
    public void updateFinalAnswerByTraceId(String traceId, String finalAnswer) {
        if (isBlank(traceId)) { // traceId 为空时无法定位同一轮记录。
            log.debug("[ToolCallLog] skip update final answer, traceId is blank"); // 空 traceId 属于可忽略场景。
            return; // 直接返回。
        }

        String safeTraceId = safeText(traceId, TRACE_ID_MAX_LENGTH); // 保持查询条件和入库 traceId 长度一致。
        long recordCount = count(new LambdaQueryWrapper<ToolCallLog>() // 只统计是否存在记录，避免加载 resultJson/finalAnswer 大字段。
                .eq(ToolCallLog::getTraceId, safeTraceId)); // 按 trace_id 精确匹配。
        if (recordCount <= 0) { // 没有记录时无需更新。
            log.debug("[ToolCallLog] update final answer skipped, no log found, traceId: {}", safeTraceId); // 普通no-tool聊天没有日志时安静返回。
            return; // 直接返回。
        }

        update(new LambdaUpdateWrapper<ToolCallLog>() // 使用批量更新回填同一 traceId 下的所有日志。
                .eq(ToolCallLog::getTraceId, safeTraceId) // 限定同一轮调用链路。
                .set(ToolCallLog::getFinalAnswer, safeText(finalAnswer, FINAL_ANSWER_MAX_LENGTH)) // finalAnswer 可为空，长度受控。
                .set(ToolCallLog::getUpdateTime, LocalDateTime.now())); // 刷新更新时间。
        log.info("[ToolCallLog] update final answer, traceId: {}", safeTraceId); // 不打印完整 finalAnswer。
    }

    private String resolveTraceId(String traceId) {
        if (isBlank(traceId)) { // 没有传入 traceId 时生成新的追踪 ID。
            return UUID.randomUUID().toString().replace("-", ""); // 生成无横线 traceId，适配当前聊天请求追踪ID风格。
        }
        return safeText(traceId, TRACE_ID_MAX_LENGTH); // 传入 traceId 时按数据库字段长度截断。
    }

    private String resolveToolName(String toolName) {
        if (isBlank(toolName)) { // 工具名称缺失时使用兜底值。
            return UNKNOWN_TOOL_NAME; // 避免 tool_name 为空导致后续排查困难。
        }
        return safeText(toolName, TOOL_NAME_MAX_LENGTH); // 控制工具名称长度。
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty(); // 统一空字符串判断。
    }

    private String safeText(String text, int maxLength) {
        if (text == null) { // null 保持为 null。
            return null; // 避免无意义空字符串覆盖。
        }
        if (maxLength <= 0 || text.length() <= maxLength) { // 未超过最大长度时直接返回。
            return text; // 保留原始内容。
        }
        return text.substring(0, maxLength); // 超长内容截断，避免数据库字段异常。
    }
}

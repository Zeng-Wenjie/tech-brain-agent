package com.agent.service.impl;

import com.agent.entity.ToolCallLog;
import com.agent.entity.dto.PageDTO;
import com.agent.entity.dto.ToolCallLogCreateRequest;
import com.agent.entity.dto.ToolCallLogPageRequest;
import com.agent.entity.dto.ToolCallStatsRequest;
import com.agent.entity.vo.ToolCallLogVO;
import com.agent.entity.vo.ToolCallStatsVO;
import com.agent.mapper.ToolCallLogMapper;
import com.agent.service.ToolCallLogService;
import com.agent.utils.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tool Calling 调用日志服务实现。
 *
 * <p>适用场景：提供 tool_call_log 表的基础写入、执行结果更新、最终回答回填、
 * 后台分页查询、单条详情查询和按工具名称统计能力。</p>
 * <p>调用链：工具执行链路通过 createRunningLog、markSuccess、markFailed 和
 * updateFinalAnswerByTraceId 维护日志生命周期；后台接口通过 pageToolCallLogs 和
 * getToolCallLogDetail 查询日志，statByToolName 汇总当前用户的工具调用情况。</p>
 * <p>边界说明：本实现只访问已存在的 tool_call_log 表，不创建表，不执行建表 SQL，
 * 不修改数据库结构，不改变 ragSearch、summarizeArticle、summary_result 或聊天主链路。</p>
 */
@Slf4j // 使用日志对象记录必要状态，不打印完整 argumentsJson、resultJson、finalAnswer。
@Service // 注册为 Spring Bean，供 Tool Calling 链路和后台查询接口注入。
public class ToolCallLogServiceImpl extends ServiceImpl<ToolCallLogMapper, ToolCallLog> implements ToolCallLogService { // 工具调用日志服务实现。

    private static final String UNKNOWN_TOOL_NAME = "UNKNOWN"; // 工具名称缺失时使用的兜底值。
    private static final int TRACE_ID_MAX_LENGTH = 64; // trace_id 字段最大长度。
    private static final int TOOL_NAME_MAX_LENGTH = 100; // tool_name 字段最大长度。
    private static final int TOOL_TYPE_MAX_LENGTH = 50; // tool_type 字段最大长度。
    private static final int CALL_SOURCE_MAX_LENGTH = 50; // call_source 字段最大长度。
    private static final int ROUTE_REASON_MAX_LENGTH = 255; // route_reason 字段最大长度。
    private static final int USER_MESSAGE_MAX_LENGTH = 2000; // user_message 入库最大长度。
    private static final int ARGUMENTS_JSON_MAX_LENGTH = 10000; // arguments_json 入库最大长度。
    private static final int RESULT_JSON_MAX_LENGTH = 50000; // result_json 入库最大长度。
    private static final int FINAL_ANSWER_MAX_LENGTH = 50000; // final_answer 入库最大长度。
    private static final int ERROR_MESSAGE_MAX_LENGTH = 5000; // error_message 入库最大长度。
    private static final int DEFAULT_PAGE_NUM = 1; // 默认分页页码。
    private static final int DEFAULT_PAGE_SIZE = 10; // 默认每页数量。
    private static final int MAX_PAGE_SIZE = 100; // 后台查询最大每页数量。
    private static final int USER_MESSAGE_PREVIEW_LENGTH = 80; // 用户输入预览长度。
    private static final int CONTENT_PREVIEW_LENGTH = 120; // 工具参数、结果、回答和错误预览长度。

    @Override // 创建运行中的工具调用日志。
    public Long createRunningLog(ToolCallLogCreateRequest request) {
        if (request == null) { // 创建日志必须有请求对象。
            throw new IllegalArgumentException("ToolCallLogCreateRequest cannot be null"); // 参数非法时直接抛出，避免写入脏日志。
        }

        LocalDateTime now = LocalDateTime.now(); // 创建和更新时间使用同一个时间点。
        String traceId = resolveTraceId(request.getTraceId()); // traceId 为空时自动生成无横线 UUID。
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
        logRecord.setCreateTime(now); // 手动设置创建时间。
        logRecord.setUpdateTime(now); // 手动设置更新时间。
        save(logRecord); // 使用 MyBatis-Plus 保存记录并回填自增 ID。
        log.info("[ToolCallLog] create running log, traceId: {}, toolName: {}", traceId, toolName); // 只打印 traceId 和工具名。
        return logRecord.getId(); // 返回自增主键，供后续成功或失败更新使用。
    }

    @Override // 标记工具调用成功。
    public void markSuccess(Long id, String resultJson, Long durationMs) {
        if (id == null) { // 日志 ID 为空时无法定位记录。
            log.warn("[ToolCallLog] skip mark success, id is null"); // 只记录安全提示。
            return; // 直接返回，不影响主链路。
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
            return; // 直接返回，不影响主链路。
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
        log.info("[ToolCallLog] mark failed, id: {}, durationMs: {}", id, durationMs); // 不打印完整错误内容。
    }

    @Override // 按 traceId 回填同一轮全部工具调用记录的最终回答。
    public void updateFinalAnswerByTraceId(String traceId, String finalAnswer) {
        if (isBlank(traceId)) { // traceId 为空时无法定位同一轮记录。
            log.debug("[ToolCallLog] skip update final answer, traceId is blank"); // 空 traceId 属于可忽略场景。
            return; // 直接返回。
        }

        String safeTraceId = safeText(traceId, TRACE_ID_MAX_LENGTH); // 保持查询条件和入库 traceId 长度一致。
        long recordCount = count(new LambdaQueryWrapper<ToolCallLog>() // 只统计是否存在记录，避免加载大字段。
                .eq(ToolCallLog::getTraceId, safeTraceId)); // 按 trace_id 精确匹配。
        if (recordCount <= 0) { // 没有记录时无需更新。
            log.debug("[ToolCallLog] update final answer skipped, no log found, traceId: {}", safeTraceId); // 普通 no-tool 聊天安静返回。
            return; // 直接返回。
        }

        update(new LambdaUpdateWrapper<ToolCallLog>() // 使用批量更新回填同一 traceId 下的所有日志。
                .eq(ToolCallLog::getTraceId, safeTraceId) // 限定同一轮调用链路。
                .set(ToolCallLog::getFinalAnswer, safeText(finalAnswer, FINAL_ANSWER_MAX_LENGTH)) // finalAnswer 可为空，长度受控。
                .set(ToolCallLog::getUpdateTime, LocalDateTime.now())); // 刷新更新时间。
        log.info("[ToolCallLog] update final answer, traceId: {}", safeTraceId); // 不打印完整 finalAnswer。
    }

    @Override // 分页查询工具调用日志。
    public PageDTO<ToolCallLogVO> pageToolCallLogs(ToolCallLogPageRequest request) {
        ToolCallLogPageRequest safeRequest = request == null ? new ToolCallLogPageRequest() : request; // 请求为空时使用默认分页参数。
        Long queryUserId = resolveQueryUserId(safeRequest.getUserId()); // 用户隔离字段，Controller 会强制写入当前用户。
        if (queryUserId == null) { // 无法识别当前用户时不返回任何日志。
            return emptyPage(); // 防止误查全量日志。
        }

        Page<ToolCallLog> page = new Page<>(resolvePageNum(safeRequest.getPageNum()), resolvePageSize(safeRequest.getPageSize())); // 构造分页对象。
        LambdaQueryWrapper<ToolCallLog> wrapper = buildPageWrapper(safeRequest, queryUserId); // 构造筛选条件。
        page(page, wrapper); // 执行 MyBatis-Plus 分页查询。

        PageDTO<ToolCallLogVO> pageDTO = new PageDTO<>(); // 封装项目统一分页结果。
        pageDTO.setTotal(page.getTotal()); // 写入总记录数。
        pageDTO.setPages(page.getPages()); // 写入总页数。
        pageDTO.setList(page.getRecords().stream().map(this::toPageVO).collect(Collectors.toList())); // 列表只返回预览字段和基础字段。
        return pageDTO; // 返回分页结果。
    }

    @Override // 查询单条工具调用日志详情。
    public ToolCallLogVO getToolCallLogDetail(Long id, Long currentUserId) {
        if (id == null || currentUserId == null) { // 详情查询必须同时具备日志 ID 和当前用户 ID。
            return null; // 参数不足时返回空，由 Controller 转换为统一错误。
        }
        ToolCallLog logRecord = getOne(new LambdaQueryWrapper<ToolCallLog>() // 按 ID 和当前用户查询，防止越权读取。
                .eq(ToolCallLog::getId, id) // 限定日志 ID。
                .eq(ToolCallLog::getUserId, currentUserId) // 强制当前用户隔离。
                .last("LIMIT 1")); // 明确只取一条记录。
        return toDetailVO(logRecord); // 转换为详情 VO。
    }

    @Override // 按工具名称统计当前用户的工具调用情况。
    public List<ToolCallStatsVO> statByToolName(ToolCallStatsRequest request) {
        Long currentUserId = UserContext.getUserId(); // 统计接口必须从登录上下文读取用户 ID。
        if (currentUserId == null) { // 没有登录用户时不返回任何统计数据。
            return Collections.emptyList(); // 防止误查全量工具调用日志。
        }

        ToolCallStatsRequest safeRequest = request == null ? new ToolCallStatsRequest() : request; // 请求为空时使用无筛选统计。
        log.info("[ToolCallLog] stat by tool name, userId: {}", currentUserId); // 只打印用户 ID，不打印完整筛选条件。
        QueryWrapper<ToolCallLog> wrapper = buildStatsWrapper(safeRequest, currentUserId); // 构造分组统计查询条件。
        List<Map<String, Object>> rows = listMaps(wrapper); // 使用 MyBatis-Plus 执行聚合查询并返回 Map 结果。
        return rows.stream().map(this::toStatsVO).collect(Collectors.toList()); // 转换为后台统计 VO。
    }

    private QueryWrapper<ToolCallLog> buildStatsWrapper(ToolCallStatsRequest request, Long currentUserId) {
        QueryWrapper<ToolCallLog> wrapper = new QueryWrapper<>(); // 创建 MyBatis-Plus 普通查询条件，便于写聚合列。
        wrapper.select(
                "tool_name AS toolName", // 返回工具名称。
                "tool_type AS toolType", // 返回工具类型。
                "COUNT(*) AS totalCount", // 统计调用总次数。
                "SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS successCount", // 统计成功次数。
                "SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS failureCount", // 统计失败次数。
                "CASE WHEN COUNT(*) = 0 THEN 0 ELSE SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) * 1.0 / COUNT(*) END AS failureRate", // 失败率使用 1.0 避免整数除法。
                "AVG(duration_ms) AS avgDurationMs" // 平均耗时，数据库会自动忽略 null。
        );
        wrapper.eq("user_id", currentUserId); // 始终按当前用户过滤，禁止统计其它用户日志。
        wrapper.eq(hasText(request.getToolName()), "tool_name", trimToNull(request.getToolName())); // toolName 有效时精确筛选，忽略 Swagger 默认 string。
        wrapper.eq(request.getConversationId() != null, "conversation_id", request.getConversationId()); // conversationId 非空时精确筛选。
        wrapper.eq(hasText(request.getCallSource()), "call_source", trimToNull(request.getCallSource())); // callSource 有效时精确筛选，忽略 Swagger 默认 string。
        wrapper.ge(request.getStartTime() != null, "create_time", request.getStartTime()); // startTime 非空时限定统计起始时间。
        wrapper.le(request.getEndTime() != null, "create_time", request.getEndTime()); // endTime 非空时限定统计结束时间。
        wrapper.groupBy("tool_name", "tool_type"); // 按工具名称和工具类型分组统计。
        wrapper.orderByDesc("totalCount"); // 默认按调用量倒序，优先展示高频工具。
        return wrapper; // 返回统计查询条件。
    }

    private ToolCallStatsVO toStatsVO(Map<String, Object> row) {
        ToolCallStatsVO vo = new ToolCallStatsVO(); // 创建统计返回对象。
        vo.setToolName(toStringValue(readValue(row, "toolName", "tool_name"))); // 写入工具名称。
        vo.setToolType(toStringValue(readValue(row, "toolType", "tool_type"))); // 写入工具类型。
        vo.setTotalCount(toLongValue(readValue(row, "totalCount", "total_count"))); // 写入调用总次数。
        vo.setSuccessCount(toLongValue(readValue(row, "successCount", "success_count"))); // 写入成功次数。
        vo.setFailureCount(toLongValue(readValue(row, "failureCount", "failure_count"))); // 写入失败次数。
        vo.setFailureRate(toScaledBigDecimal(readValue(row, "failureRate", "failure_rate"), 4)); // 失败率保留四位小数。
        vo.setAvgDurationMs(toScaledBigDecimal(readValue(row, "avgDurationMs", "avg_duration_ms"), 2)); // 平均耗时保留两位小数，null 返回 0。
        return vo; // 返回统计对象。
    }

    private LambdaQueryWrapper<ToolCallLog> buildPageWrapper(ToolCallLogPageRequest request, Long queryUserId) {
        LambdaQueryWrapper<ToolCallLog> wrapper = new LambdaQueryWrapper<>(); // 创建 MyBatis-Plus 查询条件。
        wrapper.eq(ToolCallLog::getUserId, queryUserId); // 始终按当前用户过滤，禁止查询其它用户日志。
        wrapper.eq(hasText(request.getTraceId()), ToolCallLog::getTraceId, trimToNull(request.getTraceId())); // traceId 有效时精确查询，忽略 Swagger 默认 string。
        wrapper.eq(request.getConversationId() != null, ToolCallLog::getConversationId, request.getConversationId()); // conversationId 非空时精确查询。
        wrapper.eq(hasText(request.getToolName()), ToolCallLog::getToolName, trimToNull(request.getToolName())); // toolName 有效时精确查询，忽略 Swagger 默认 string。
        wrapper.eq(hasText(request.getToolType()), ToolCallLog::getToolType, trimToNull(request.getToolType())); // toolType 有效时精确查询，忽略 Swagger 默认 string。
        wrapper.eq(hasText(request.getCallSource()), ToolCallLog::getCallSource, trimToNull(request.getCallSource())); // callSource 有效时精确查询，忽略 Swagger 默认 string。
        wrapper.eq(request.getSuccess() != null, ToolCallLog::getSuccess, request.getSuccess()); // success 非空时精确查询。
        wrapper.ge(request.getStartTime() != null, ToolCallLog::getCreateTime, request.getStartTime()); // startTime 非空时查询创建时间下限。
        wrapper.le(request.getEndTime() != null, ToolCallLog::getCreateTime, request.getEndTime()); // endTime 非空时查询创建时间上限。
        wrapper.orderByDesc(ToolCallLog::getCreateTime).orderByDesc(ToolCallLog::getId); // 默认按创建时间倒序，再按 ID 倒序稳定排序。
        return wrapper; // 返回查询条件。
    }

    private ToolCallLogVO toPageVO(ToolCallLog logRecord) {
        if (logRecord == null) { // 空实体无法转换。
            return null; // 直接返回空。
        }
        ToolCallLogVO vo = buildBaseVO(logRecord); // 先写入列表基础字段。
        vo.setUserMessagePreview(preview(logRecord.getUserMessage(), USER_MESSAGE_PREVIEW_LENGTH)); // 写入用户输入预览。
        vo.setArgumentsPreview(preview(logRecord.getArgumentsJson(), CONTENT_PREVIEW_LENGTH)); // 写入工具入参预览。
        vo.setResultPreview(preview(logRecord.getResultJson(), CONTENT_PREVIEW_LENGTH)); // 写入工具结果预览。
        vo.setFinalAnswerPreview(preview(logRecord.getFinalAnswer(), CONTENT_PREVIEW_LENGTH)); // 写入最终回答预览。
        vo.setErrorMessagePreview(preview(logRecord.getErrorMessage(), CONTENT_PREVIEW_LENGTH)); // 写入错误信息预览。
        return vo; // 分页列表不返回完整大字段。
    }

    private ToolCallLogVO toDetailVO(ToolCallLog logRecord) {
        if (logRecord == null) { // 空实体无法转换。
            return null; // 直接返回空。
        }
        ToolCallLogVO vo = toPageVO(logRecord); // 详情同时保留基础字段和预览字段。
        vo.setUserMessage(logRecord.getUserMessage()); // 详情返回完整用户输入。
        vo.setArgumentsJson(logRecord.getArgumentsJson()); // 详情返回完整工具入参。
        vo.setResultJson(logRecord.getResultJson()); // 详情返回完整工具结果。
        vo.setFinalAnswer(logRecord.getFinalAnswer()); // 详情返回完整最终回答。
        vo.setErrorMessage(logRecord.getErrorMessage()); // 详情返回完整错误信息。
        return vo; // 返回详情对象。
    }

    private ToolCallLogVO buildBaseVO(ToolCallLog logRecord) {
        ToolCallLogVO vo = new ToolCallLogVO(); // 创建返回对象。
        vo.setId(logRecord.getId()); // 写入日志 ID。
        vo.setTraceId(logRecord.getTraceId()); // 写入 traceId。
        vo.setConversationId(logRecord.getConversationId()); // 写入会话 ID。
        vo.setUserId(logRecord.getUserId()); // 写入用户 ID。
        vo.setToolName(logRecord.getToolName()); // 写入工具名称。
        vo.setToolType(logRecord.getToolType()); // 写入工具类型。
        vo.setCallSource(logRecord.getCallSource()); // 写入调用来源。
        vo.setRouteReason(logRecord.getRouteReason()); // 写入路由原因。
        vo.setSuccess(logRecord.getSuccess()); // 写入执行状态。
        vo.setDurationMs(logRecord.getDurationMs()); // 写入执行耗时。
        vo.setCreateTime(logRecord.getCreateTime()); // 写入创建时间。
        vo.setUpdateTime(logRecord.getUpdateTime()); // 写入更新时间。
        return vo; // 返回基础字段对象。
    }

    private PageDTO<ToolCallLogVO> emptyPage() {
        PageDTO<ToolCallLogVO> pageDTO = new PageDTO<>(); // 创建空分页结果。
        pageDTO.setTotal(0L); // 空结果总数为 0。
        pageDTO.setPages(0L); // 空结果总页数为 0。
        pageDTO.setList(Collections.emptyList()); // 空结果列表。
        return pageDTO; // 返回空分页。
    }

    private Long resolveQueryUserId(Long requestUserId) {
        if (requestUserId != null) { // Controller 已强制覆盖为当前用户时优先使用。
            return requestUserId; // 返回查询用户 ID。
        }
        return UserContext.getUserId(); // 兜底读取当前线程登录用户 ID。
    }

    private long resolvePageNum(Integer pageNum) {
        if (pageNum == null || pageNum < 1) { // 页码为空或小于 1 时使用默认值。
            return DEFAULT_PAGE_NUM; // 返回默认第一页。
        }
        return pageNum; // 返回合法页码。
    }

    private long resolvePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) { // 每页数量为空或小于 1 时使用默认值。
            return DEFAULT_PAGE_SIZE; // 返回默认每页数量。
        }
        return Math.min(pageSize, MAX_PAGE_SIZE); // 限制最大每页数量，避免一次查询过多。
    }

    private String resolveTraceId(String traceId) {
        if (isBlank(traceId)) { // 没有传入 traceId 时生成新的追踪 ID。
            return UUID.randomUUID().toString().replace("-", ""); // 生成无横线 UUID。
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

    private boolean hasText(String value) {
        if (value == null) { // null 不参与查询条件。
            return false; // 返回无有效文本。
        }
        String trimmedValue = value.trim(); // 去除首尾空白后再判断。
        if (trimmedValue.isEmpty()) { // 空字符串不参与查询条件。
            return false; // 返回无有效文本。
        }
        return !"string".equalsIgnoreCase(trimmedValue); // Swagger 默认 string 不参与查询条件。
    }

    private String trimToNull(String text) {
        if (!hasText(text)) { // 空字符串和 Swagger 默认 string 统一转为空。
            return null; // 返回空值。
        }
        return text.trim(); // 返回去除首尾空白后的文本。
    }

    private Object readValue(Map<String, Object> row, String... keys) {
        if (row == null || keys == null) { // 聚合查询结果为空时没有可读取值。
            return null; // 返回空值。
        }
        for (String key : keys) { // 先按预期别名读取。
            if (row.containsKey(key)) { // Map 中存在当前别名。
                return row.get(key); // 返回别名对应的值。
            }
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) { // 兼容部分驱动返回大小写不同的列别名。
            for (String key : keys) { // 遍历候选别名。
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) { // 忽略大小写匹配。
                    return entry.getValue(); // 返回匹配到的值。
                }
            }
        }
        return null; // 未匹配到时返回空。
    }

    private String toStringValue(Object value) {
        return value == null ? null : String.valueOf(value); // 统一把统计列转换为字符串。
    }

    private Long toLongValue(Object value) {
        if (value == null) { // 统计值为空时返回 0。
            return 0L; // 兜底为 0。
        }
        if (value instanceof Number) { // 数据库聚合值通常是 Number。
            return ((Number) value).longValue(); // 转为 Long。
        }
        try { // 兼容驱动把数字返回为字符串的情况。
            return Long.parseLong(String.valueOf(value)); // 按字符串解析 Long。
        } catch (NumberFormatException e) { // 解析失败说明返回值异常。
            log.debug("[ToolCallLog] parse long stat value failed"); // 不打印原始值，避免日志噪声。
            return 0L; // 返回安全默认值。
        }
    }

    private BigDecimal toScaledBigDecimal(Object value, int scale) {
        BigDecimal decimalValue = BigDecimal.ZERO; // 默认数值为 0。
        if (value instanceof BigDecimal) { // 数据库可能直接返回 BigDecimal。
            decimalValue = (BigDecimal) value; // 保留数据库返回值。
        } else if (value instanceof Number) { // 数据库也可能返回 Double、Long 等 Number。
            decimalValue = BigDecimal.valueOf(((Number) value).doubleValue()); // 转为 BigDecimal 便于统一保留小数位。
        } else if (value != null) { // 非空字符串形式也尝试解析。
            try { // 兼容驱动返回字符串数字。
                decimalValue = new BigDecimal(String.valueOf(value)); // 按字符串构造 BigDecimal。
            } catch (NumberFormatException e) { // 解析失败时保持默认值。
                log.debug("[ToolCallLog] parse decimal stat value failed"); // 不打印原始值。
            }
        }
        return decimalValue.setScale(scale, RoundingMode.HALF_UP); // 按接口约定保留固定小数位。
    }

    private String preview(String text, int maxLength) {
        if (text == null) { // 空内容保持为空。
            return null; // 返回空值。
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' '); // 预览内容去掉换行，避免列表展示错位。
        return safeText(normalized, maxLength); // 按指定长度截断预览。
    }

    private String safeText(String text, int maxLength) {
        if (text == null) { // 空内容保持为空。
            return null; // 返回空值。
        }
        if (maxLength <= 0 || text.length() <= maxLength) { // 未超过最大长度时直接返回。
            return text; // 保留原始内容。
        }
        return text.substring(0, maxLength); // 超长内容截断，避免数据库字段异常或列表过大。
    }
}

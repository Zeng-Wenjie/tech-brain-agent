package com.agent.service.impl;

import com.agent.entity.DevActionLog;
import com.agent.entity.ToolCallLog;
import com.agent.entity.dto.DevActionLogSaveCommand;
import com.agent.mapper.DevActionLogMapper;
import com.agent.mapper.ToolCallLogMapper;
import com.agent.service.DevActionLogService;
import com.agent.toolcalling.devlog.DevActionLogSaveResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 开发行为日志服务实现（P5.9 代码分析结果落库最小能力）。
 *
 * <p>适用场景：把一次 analyzeCode 统一分析结果保存到 dev_action_log，支撑“分析并保存”和“保存上一条分析结果”。
 * action_type 由 analysisType 推导，title/summary 在用户未显式指定时按 analysisType 与目标自动生成，
 * result_json 控制最大长度并只保存 analyzeCode 已脱敏的统一结果（workspace 相对路径），通过 trace_id 与
 * tool_call_log_id 与 tool_call_log 关联。</p>
 *
 * <p>调用链：AnalyzeCodeTool（saveToDevLog=true）直接调用 saveCodeAnalysisLog；用户显式“保存上一条”时
 * saveLastCodeAnalysisLog 先用 ToolCallLogMapper 读取当前会话最近一条成功 analyzeCode 的 tool_call_log，
 * 再复用 saveCodeAnalysisLog 落库。</p>
 *
 * <p>边界说明：本实现只访问 dev_action_log 与读取 tool_call_log，不创建表、不执行建表 SQL、不改库结构，
 * 不做 P6 完整行为日志，不记录 patch / 文件修改 / 编译 / 回滚，保存异常一律捕获，绝不影响 analyzeCode 主流程。</p>
 */
@Slf4j // 记录关键状态，不打印完整 result_json，避免日志噪声与敏感信息。
@Service // 注册为 Spring Bean，供 AnalyzeCodeTool 与 ChatMessageServiceImpl 的开发日志回调注入。
public class DevActionLogServiceImpl extends ServiceImpl<DevActionLogMapper, DevActionLog> implements DevActionLogService { // 开发行为日志服务实现。

    private static final String ANALYZE_CODE_TOOL_NAME = "analyzeCode"; // 唯一对外代码分析 Tool 名称，用于定位最近一条分析日志。
    private static final String STATUS_SUCCESS = "SUCCESS"; // 开发日志保存状态：成功。
    private static final int TRACE_ID_MAX_LENGTH = 64; // trace_id 字段最大长度。
    private static final int ANALYSIS_TYPE_MAX_LENGTH = 30; // analysis_type 字段最大长度。
    private static final int TARGET_TYPE_MAX_LENGTH = 30; // target_type 字段最大长度。
    private static final int ACTION_TYPE_MAX_LENGTH = 50; // action_type 字段最大长度。
    private static final int TARGET_PATH_MAX_LENGTH = 500; // target_path 字段最大长度。
    private static final int CLASS_NAME_MAX_LENGTH = 200; // class_name 字段最大长度。
    private static final int METHOD_NAME_MAX_LENGTH = 200; // method_name 字段最大长度。
    private static final int ENDPOINT_MAX_LENGTH = 300; // endpoint 字段最大长度。
    private static final int TOOL_NAME_MAX_LENGTH = 100; // tool_name 字段最大长度。
    private static final int EVENT_NAME_MAX_LENGTH = 100; // event_name 字段最大长度。
    private static final int TITLE_MAX_LENGTH = 255; // title 字段最大长度。
    private static final int SUMMARY_MAX_LENGTH = 1000; // summary 字段最大长度。
    private static final int RESULT_JSON_MAX_LENGTH = 1_000_000; // result_json 入库最大长度（约 1MB），超出则截断。

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(); // 仅用于解析 analyzeCode 统一结果，提取 summary/risks/testCases。

    private final ToolCallLogMapper toolCallLogMapper; // 复用 tool_call_log，用于“保存上一条分析结果”读取最近一条 analyzeCode 日志。

    public DevActionLogServiceImpl(ToolCallLogMapper toolCallLogMapper) { // 构造器注入 tool_call_log Mapper。
        this.toolCallLogMapper = toolCallLogMapper; // 保存 Mapper 引用。
    }

    @Override // 保存一次代码分析结果到 dev_action_log，失败返回 null，绝不抛出影响主流程。
    public Long saveCodeAnalysisLog(DevActionLogSaveCommand command) {
        if (command == null || isBlank(command.getResultJson())) { // 没有结果 JSON 时不保存空日志。
            log.warn("[DevActionLog] skip save, command or resultJson is blank"); // 只记录安全提示。
            return null; // 返回 null，调用方按保存失败处理。
        }
        try {
            JsonNode resultNode = readTreeSafely(command.getResultJson()); // 解析 analyzeCode 统一结果，用于提取 summary。
            String analysisType = normalizeAnalysisType(command.getAnalysisType(), resultNode); // 统一 analysisType（大写）。
            LocalDateTime now = LocalDateTime.now(); // 创建与更新时间使用同一时间点。

            DevActionLog devLog = new DevActionLog(); // 构造开发行为日志记录。
            devLog.setUserId(command.getUserId()); // 写入用户 ID。
            devLog.setConversationId(command.getConversationId()); // 写入会话 ID。
            devLog.setTraceId(safeText(command.getTraceId(), TRACE_ID_MAX_LENGTH)); // 写入与 tool_call_log 一致的 traceId。
            devLog.setToolCallLogId(resolveToolCallLogId(command)); // 写入来源 tool_call_log.id（缺失时按 traceId 回查），便于追踪。
            devLog.setActionType(safeText(resolveActionType(analysisType), ACTION_TYPE_MAX_LENGTH)); // 由 analysisType 推导 action_type。
            devLog.setAnalysisType(safeText(analysisType, ANALYSIS_TYPE_MAX_LENGTH)); // 写入 analysisType。
            devLog.setTargetType(safeText(resolveTargetType(command, analysisType), TARGET_TYPE_MAX_LENGTH)); // 写入目标类型。
            devLog.setTargetPath(safeText(command.getTargetPath(), TARGET_PATH_MAX_LENGTH)); // 写入 workspace 相对路径，绝不含绝对路径。
            devLog.setClassName(safeText(command.getClassName(), CLASS_NAME_MAX_LENGTH)); // 写入类名。
            devLog.setMethodName(safeText(command.getMethodName(), METHOD_NAME_MAX_LENGTH)); // 写入方法名。
            devLog.setEndpoint(safeText(command.getEndpoint(), ENDPOINT_MAX_LENGTH)); // 写入接口路径。
            devLog.setToolName(safeText(command.getToolName(), TOOL_NAME_MAX_LENGTH)); // 写入 AI Tool 名称。
            devLog.setEventName(safeText(command.getEventName(), EVENT_NAME_MAX_LENGTH)); // 写入 SSE 事件名。
            devLog.setTitle(safeText(resolveTitle(command, analysisType), TITLE_MAX_LENGTH)); // title 为空时自动生成。
            devLog.setSummary(safeText(resolveSummary(command, analysisType, resultNode), SUMMARY_MAX_LENGTH)); // summary 为空时自动提取。
            devLog.setResultJson(safeText(command.getResultJson(), RESULT_JSON_MAX_LENGTH)); // 控制 result_json 长度，超出截断。
            devLog.setStatus(STATUS_SUCCESS); // P5.9 只保存分析成功结果，状态固定 SUCCESS。
            devLog.setErrorMsg(null); // 成功保存时无错误原因。
            devLog.setCreatedAt(now); // 写入创建时间。
            devLog.setUpdatedAt(now); // 写入更新时间。

            save(devLog); // 使用 MyBatis-Plus 保存并回填自增 ID。
            log.info("[DevActionLog] save code analysis log, id: {}, analysisType: {}, traceId: {}",
                    devLog.getId(), analysisType, devLog.getTraceId()); // 只打印 ID 与 analysisType，不打印 result_json。
            return devLog.getId(); // 返回开发日志 ID。
        } catch (Exception e) {
            log.warn("[DevActionLog] save code analysis log failed, traceId: {}, error: {}",
                    command.getTraceId(), e.getMessage(), e); // 保存失败只记录 warn，不影响主流程。
            return null; // 失败返回 null。
        }
    }

    @Override // 读取当前会话最近一条成功 analyzeCode 结果并保存到开发日志。
    public DevActionLogSaveResult saveLastCodeAnalysisLog(Long userId, Long conversationId, String traceId) {
        try {
            ToolCallLog lastLog = findLastAnalyzeCodeLog(userId, conversationId); // 查询最近一条成功 analyzeCode 日志。
            if (lastLog == null || isBlank(lastLog.getResultJson())) { // 没有可保存的上一条分析结果。
                log.info("[DevActionLog] no last analyzeCode result to save, conversationId: {}, userId: {}",
                        conversationId, userId); // 只打印归属 ID。
                return DevActionLogSaveResult.notFound(); // 返回未找到，路由层提示先执行一次分析。
            }
            DevActionLogSaveCommand command = buildCommandFromToolCallLog(lastLog, userId, conversationId, traceId); // 用上一条 tool_call_log 复原保存入参。
            Long devLogId = saveCodeAnalysisLog(command); // 复用统一保存逻辑。
            if (devLogId == null) { // 落库失败。
                return DevActionLogSaveResult.failed("保存开发日志失败，请稍后重试。"); // 返回失败，路由层提示保存失败。
            }
            return DevActionLogSaveResult.success(devLogId); // 返回成功并携带日志 ID。
        } catch (Exception e) {
            log.warn("[DevActionLog] save last analyzeCode result failed, conversationId: {}, userId: {}, error: {}",
                    conversationId, userId, e.getMessage(), e); // 失败只记录 warn。
            return DevActionLogSaveResult.failed("保存开发日志失败，请稍后重试。"); // 异常也按失败返回，不抛出。
        }
    }

    private Long resolveToolCallLogId(DevActionLogSaveCommand command) { // 解析来源 tool_call_log.id：优先用入参，缺失时按 traceId 回查。
        if (command.getToolCallLogId() != null) { // 调用方已显式给出。
            return command.getToolCallLogId(); // 直接使用。
        }
        if (isBlank(command.getTraceId())) { // 没有 traceId 时无法回查。
            return null; // 返回空，tool_call_log_id 可为空。
        }
        try {
            LambdaQueryWrapper<ToolCallLog> wrapper = new LambdaQueryWrapper<>(); // 构造查询条件。
            wrapper.eq(ToolCallLog::getTraceId, safeText(command.getTraceId(), TRACE_ID_MAX_LENGTH)); // 按 traceId 精确匹配。
            wrapper.eq(ToolCallLog::getToolName, ANALYZE_CODE_TOOL_NAME); // 只取 analyzeCode 调用记录。
            wrapper.orderByDesc(ToolCallLog::getId); // 取同一 traceId 下最近一条（可能仍在运行中 success=0）。
            wrapper.last("LIMIT 1"); // 只取一条。
            ToolCallLog runningLog = toolCallLogMapper.selectOne(wrapper); // 查询当前轮 analyzeCode 日志。
            return runningLog == null ? null : runningLog.getId(); // 命中则返回其 id。
        } catch (Exception e) { // 回查失败不影响保存。
            log.debug("[DevActionLog] resolve tool_call_log_id by traceId failed"); // 仅 debug，不打印细节。
            return null; // 返回空。
        }
    }

    private ToolCallLog findLastAnalyzeCodeLog(Long userId, Long conversationId) { // 查询当前会话最近一条成功 analyzeCode 日志。
        LambdaQueryWrapper<ToolCallLog> wrapper = new LambdaQueryWrapper<>(); // 构造查询条件。
        wrapper.eq(userId != null, ToolCallLog::getUserId, userId); // 按用户隔离。
        wrapper.eq(conversationId != null, ToolCallLog::getConversationId, conversationId); // 限定当前会话。
        wrapper.eq(ToolCallLog::getToolName, ANALYZE_CODE_TOOL_NAME); // 只取 analyzeCode 调用记录。
        wrapper.eq(ToolCallLog::getSuccess, 1); // 只取技术执行成功的分析。
        wrapper.orderByDesc(ToolCallLog::getId); // 最近一条优先。
        wrapper.last("LIMIT 1"); // 只取一条。
        return toolCallLogMapper.selectOne(wrapper); // 返回最近一条 analyzeCode 日志。
    }

    private DevActionLogSaveCommand buildCommandFromToolCallLog(ToolCallLog lastLog,
                                                                Long userId,
                                                                Long conversationId,
                                                                String traceId) { // 用上一条 analyzeCode 日志复原保存入参。
        JsonNode resultNode = readTreeSafely(lastLog.getResultJson()); // 解析统一分析结果。
        JsonNode target = resultNode.path("target"); // 读取统一 target 节点。
        DevActionLogSaveCommand command = new DevActionLogSaveCommand(); // 构造保存入参。
        command.setUserId(userId); // 当前用户。
        command.setConversationId(conversationId); // 当前会话。
        command.setTraceId(isBlank(lastLog.getTraceId()) ? traceId : lastLog.getTraceId()); // 优先沿用原分析的 traceId 以保持链路一致。
        command.setToolCallLogId(lastLog.getId()); // 指向来源 tool_call_log.id。
        command.setAnalysisType(resultNode.path("analysisType").asText("")); // analysisType 从结果中读取。
        command.setTargetPath(textOrNull(target.path("path"))); // workspace 相对路径。
        command.setClassName(textOrNull(target.path("className"))); // 类名。
        command.setMethodName(textOrNull(target.path("methodName"))); // 方法名。
        command.setEndpoint(textOrNull(target.path("endpoint"))); // 接口路径。
        command.setToolName(textOrNull(target.path("toolName"))); // Tool 名称。
        command.setEventName(textOrNull(target.path("eventName"))); // SSE 事件名。
        command.setResultJson(lastLog.getResultJson()); // 复用上一条分析的 result_json。
        return command; // 返回复原后的保存入参。
    }

    private String normalizeAnalysisType(String analysisType, JsonNode resultNode) { // 统一 analysisType，缺失时从结果回退。
        String type = analysisType; // 优先使用入参。
        if (isBlank(type)) { // 入参为空时从统一结果读取。
            type = resultNode.path("analysisType").asText(""); // 读取结果中的 analysisType。
        }
        return isBlank(type) ? "" : type.trim().toUpperCase(Locale.ROOT); // 统一大写，便于映射 action_type。
    }

    private String resolveActionType(String analysisType) { // 由 analysisType 推导 action_type。
        if (isBlank(analysisType)) { // 类型缺失。
            return "CODE_ANALYSIS"; // 兜底通用类型。
        }
        switch (analysisType) { // 按 analysisType 一一映射 action_type。
            case "STRUCTURE":
                return "CODE_STRUCTURE_ANALYSIS"; // 结构分析。
            case "CALL_CHAIN":
                return "CODE_CALL_CHAIN_ANALYSIS"; // 调用链分析。
            case "CONTROLLER_SERVICE":
                return "CODE_CONTROLLER_SERVICE_ANALYSIS"; // 接口 Service 链路分析。
            case "TOOL_SERVICE":
                return "CODE_TOOL_SERVICE_ANALYSIS"; // Tool Service 链路分析。
            case "SSE_EVENT_CHAIN":
                return "CODE_SSE_EVENT_CHAIN_ANALYSIS"; // SSE 事件链路分析。
            case "EXPLANATION":
                return "CODE_EXPLANATION"; // 代码说明。
            case "RISK":
                return "CODE_RISK_ANALYSIS"; // 风险点分析。
            case "TEST_STEPS":
                return "CODE_TEST_STEPS"; // 测试步骤。
            default:
                return "CODE_ANALYSIS"; // 其它类型兜底。
        }
    }

    private String resolveTargetType(DevActionLogSaveCommand command, String analysisType) { // 推导目标类型。
        if (!isBlank(command.getTargetType())) { // 调用方已显式给出。
            return command.getTargetType().trim().toUpperCase(Locale.ROOT); // 直接使用并统一大写。
        }
        if (!isBlank(command.getEndpoint())) { // 有接口路径。
            return "ENDPOINT"; // 接口目标。
        }
        if (!isBlank(command.getToolName())) { // 有 Tool 名称。
            return "TOOL"; // Tool 目标。
        }
        if (!isBlank(command.getEventName())) { // 有 SSE 事件名。
            return "SSE_EVENT"; // SSE 事件目标。
        }
        if (!isBlank(command.getClassName())) { // 有类名。
            return "CLASS"; // 类目标。
        }
        if (!isBlank(command.getMethodName())) { // 仅有方法名。
            return "METHOD"; // 方法目标。
        }
        if (!isBlank(command.getTargetPath())) { // 仅有文件路径。
            return "FILE"; // 文件目标。
        }
        return "UNKNOWN"; // 无法判断目标类型。
    }

    private String resolveTitle(DevActionLogSaveCommand command, String analysisType) { // title 为空时按 analysisType 与目标自动生成。
        if (!isBlank(command.getDevLogTitle())) { // 用户显式指定标题。
            return command.getDevLogTitle().trim(); // 直接使用。
        }
        String target = resolveDisplayTarget(command, analysisType); // 计算展示用目标名。
        switch (isBlank(analysisType) ? "" : analysisType) { // 按 analysisType 生成标题。
            case "STRUCTURE":
                return "代码结构分析：" + target; // 结构分析标题。
            case "CALL_CHAIN":
                return "调用链分析：" + target; // 调用链分析标题。
            case "CONTROLLER_SERVICE":
                return "接口 Service 链路分析：" + target; // Controller→Service 标题。
            case "TOOL_SERVICE":
                return "Tool Service 链路分析：" + target; // Tool→Service 标题。
            case "SSE_EVENT_CHAIN":
                return "SSE 事件链路分析：" + target; // SSE 事件链路标题。
            case "EXPLANATION":
                return "代码说明：" + target; // 代码说明标题。
            case "RISK":
                return "风险点分析：" + target; // 风险点分析标题。
            case "TEST_STEPS":
                return "测试步骤：" + target; // 测试步骤标题。
            default:
                return "代码分析：" + target; // 兜底标题。
        }
    }

    private String resolveDisplayTarget(DevActionLogSaveCommand command, String analysisType) { // 计算标题中的目标展示名。
        if ("CONTROLLER_SERVICE".equals(analysisType) && !isBlank(command.getEndpoint())) { // 接口链路优先 endpoint。
            return command.getEndpoint().trim(); // 返回接口路径。
        }
        if ("TOOL_SERVICE".equals(analysisType) && !isBlank(command.getToolName())) { // Tool 链路优先 toolName。
            return command.getToolName().trim(); // 返回 Tool 名称。
        }
        if ("SSE_EVENT_CHAIN".equals(analysisType) && !isBlank(command.getEventName())) { // SSE 链路优先事件名。
            return command.getEventName().trim(); // 返回事件名。
        }
        if (!isBlank(command.getClassName())) { // 其它类型优先类名。
            return command.getClassName().trim(); // 返回类名。
        }
        if (!isBlank(command.getToolName())) { // 退而用 Tool 名称。
            return command.getToolName().trim(); // 返回 Tool 名称。
        }
        if (!isBlank(command.getEndpoint())) { // 退而用接口路径。
            return command.getEndpoint().trim(); // 返回接口路径。
        }
        if (!isBlank(command.getEventName())) { // 退而用事件名。
            return command.getEventName().trim(); // 返回事件名。
        }
        if (!isBlank(command.getMethodName())) { // 退而用方法名。
            return command.getMethodName().trim(); // 返回方法名。
        }
        if (!isBlank(command.getTargetPath())) { // 最后用文件名（取相对路径末段）。
            return fileNameOf(command.getTargetPath().trim()); // 返回文件名。
        }
        return "项目代码"; // 无目标时的兜底展示名。
    }

    private String resolveSummary(DevActionLogSaveCommand command, String analysisType, JsonNode resultNode) { // summary 为空时从结果自动提取。
        if (!isBlank(command.getDevLogSummary())) { // 用户显式指定摘要。
            return command.getDevLogSummary().trim(); // 直接使用。
        }
        String topSummary = resultNode.path("summary").asText(""); // 1. 顶层 summary。
        if (!isBlank(topSummary)) { // 顶层存在 summary。
            return topSummary.trim(); // 使用顶层 summary。
        }
        JsonNode inner = resultNode.path("result"); // 内部 Analyzer 结果节点。
        String innerSummary = inner.path("summary").asText(""); // 2. result.result.summary。
        if (!isBlank(innerSummary)) { // 内部存在 summary。
            return innerSummary.trim(); // 使用内部 summary。
        }
        JsonNode risks = inner.path("risks"); // 风险点数组。
        JsonNode safePoints = inner.path("safePoints"); // 已有保护点数组。
        if (risks.isArray() || safePoints.isArray()) { // 3. 风险类结果（即使风险为空也展示保护点）。
            int riskCount = risks.isArray() ? risks.size() : 0; // 风险点数量。
            int safeCount = safePoints.isArray() ? safePoints.size() : 0; // 保护点数量。
            return "发现 " + riskCount + " 个风险点，" + safeCount + " 个已有保护点。"; // 风险摘要。
        }
        JsonNode testCases = inner.path("testCases"); // 测试用例数组。
        if (testCases.isArray() && testCases.size() > 0) { // 4. testCases 存在时统计测试用例数量。
            return "生成 " + testCases.size() + " 个测试用例，包含日志检查和回归检查。"; // 测试步骤摘要。
        }
        String typeText = isBlank(analysisType) ? "代码" : analysisType; // 5. 兜底文案使用 analysisType。
        return "已完成一次 " + typeText + " 类型的代码分析。"; // 兜底摘要。
    }

    private JsonNode readTreeSafely(String json) { // 安全解析 JSON，失败返回空对象节点。
        try {
            return OBJECT_MAPPER.readTree(isBlank(json) ? "{}" : json); // 解析结果 JSON。
        } catch (Exception e) {
            log.debug("[DevActionLog] parse result json failed"); // 解析失败不打印原文。
            return OBJECT_MAPPER.createObjectNode(); // 返回空对象，避免空指针。
        }
    }

    private String fileNameOf(String path) { // 从 workspace 相对路径取末段文件名。
        if (isBlank(path)) { // 空路径。
            return "项目代码"; // 兜底。
        }
        String normalized = path.replace('\\', '/'); // 统一分隔符。
        int slash = normalized.lastIndexOf('/'); // 末段分隔位置。
        return slash >= 0 && slash < normalized.length() - 1 ? normalized.substring(slash + 1) : normalized; // 返回文件名。
    }

    private String textOrNull(JsonNode node) { // 读取文本字段，空白返回 null。
        if (node == null || node.isMissingNode() || node.isNull()) { // 字段不存在。
            return null; // 返回 null。
        }
        String text = node.asText(""); // 读取文本。
        return isBlank(text) ? null : text; // 空白返回 null。
    }

    private boolean isBlank(String text) { // 统一空字符串判断。
        return text == null || text.trim().isEmpty(); // null 或纯空白视为空。
    }

    private String safeText(String text, int maxLength) { // 控制字段长度，避免超出数据库列。
        if (text == null) { // 空内容保持为空。
            return null; // 返回 null。
        }
        if (maxLength <= 0 || text.length() <= maxLength) { // 未超过最大长度。
            return text; // 直接返回。
        }
        return text.substring(0, maxLength); // 超长截断。
    }
}

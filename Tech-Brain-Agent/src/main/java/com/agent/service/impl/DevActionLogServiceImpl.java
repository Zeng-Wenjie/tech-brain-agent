package com.agent.service.impl;

import com.agent.entity.DevActionLog;
import com.agent.entity.ToolCallLog;
import com.agent.entity.dto.DevActionLogCreateRequest;
import com.agent.entity.dto.DevActionLogSaveCommand;
import com.agent.entity.enums.DevActionResult;
import com.agent.entity.enums.DevActionStatus;
import com.agent.entity.enums.DevActionType;
import com.agent.entity.enums.DevTargetType;
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
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 开发行为日志服务实现（P6.1 统一开发行为日志服务）。
 *
 * <p>适用场景：P6.1 把 P5.9“只保存代码分析结果”升级为“统一开发行为日志服务”。所有开发行为通过统一入口
 * {@link #saveDevAction} 落库，自动补全 intent（为什么做）、summary（自然语言摘要，供 P17 向量化召回）、
 * result（行为质量，供 P18 筛选）、targetModule / targetFile（供 P17 按模块/文件召回），并对路径脱敏、控制长度，
 * 通过 trace_id / tool_call_log_id 串起完整开发流程。</p>
 *
 * <p>调用链：AnalyzeCodeTool（saveToDevLog=true）-> {@link #saveCodeAnalysisLog}（P5.9 兼容）-> {@link #recordCodeAnalysis}
 * -> {@link #saveDevAction} -> DevActionLogMapper（MyBatis-Plus save，自动覆盖全部实体字段）-> dev_action_log；
 * 用户“保存上一条分析结果”-> {@link #saveLastCodeAnalysisLog} -> 读取最近一条 analyzeCode tool_call_log -> 同一入口。</p>
 *
 * <p>边界说明：本实现只访问 dev_action_log 与读取 tool_call_log，不创建表、不执行建表 SQL、不改库结构；
 * 不做 P7-P14 真实业务（patch/文件修改/编译/回滚），record* 预留方法只设置 actionType 后复用 saveDevAction；
 * 保存异常一律捕获，绝不影响 analyzeCode 主流程或聊天 SSE。</p>
 */
@Slf4j // 记录关键状态，不打印完整 result_json，避免日志噪声与敏感信息。
@Service // 注册为 Spring Bean，供 AnalyzeCodeTool 与 ChatMessageServiceImpl 的开发日志回调注入。
public class DevActionLogServiceImpl extends ServiceImpl<DevActionLogMapper, DevActionLog> implements DevActionLogService { // 统一开发行为日志服务实现。

    private static final String ANALYZE_CODE_TOOL_NAME = "analyzeCode"; // 唯一对外代码分析 Tool 名称，用于定位最近一条分析日志。

    // ===================== 字段长度上限（与表列宽一致，仅截断不改库）=====================
    private static final int TRACE_ID_MAX_LENGTH = 64; // trace_id 字段最大长度。
    private static final int ACTION_TYPE_MAX_LENGTH = 50; // action_type 字段最大长度。
    private static final int INTENT_MAX_LENGTH = 512; // intent 字段最大长度。
    private static final int RESULT_MAX_LENGTH = 64; // result 字段最大长度。
    private static final int ANALYSIS_TYPE_MAX_LENGTH = 30; // analysis_type 字段最大长度。
    private static final int TARGET_TYPE_MAX_LENGTH = 30; // target_type 字段最大长度。
    private static final int TARGET_MODULE_MAX_LENGTH = 255; // target_module 字段最大长度。
    private static final int TARGET_FILE_MAX_LENGTH = 512; // target_file 字段最大长度。
    private static final int TARGET_PATH_MAX_LENGTH = 500; // target_path 字段最大长度。
    private static final int CLASS_NAME_MAX_LENGTH = 200; // class_name 字段最大长度。
    private static final int METHOD_NAME_MAX_LENGTH = 200; // method_name 字段最大长度。
    private static final int ENDPOINT_MAX_LENGTH = 300; // endpoint 字段最大长度。
    private static final int TOOL_NAME_MAX_LENGTH = 100; // tool_name 字段最大长度。
    private static final int EVENT_NAME_MAX_LENGTH = 100; // event_name 字段最大长度。
    private static final int RELATED_BUG_ID_MAX_LENGTH = 128; // related_bug_id 字段最大长度。
    private static final int TITLE_MAX_LENGTH = 255; // title 字段最大长度。
    private static final int SUMMARY_MAX_LENGTH = 1000; // summary 字段最大长度。
    private static final int ERROR_MSG_MAX_LENGTH = 1000; // error_msg 字段最大长度。
    private static final int RESULT_JSON_MAX_LENGTH = 1_000_000; // result_json 入库最大长度（约 1MB），超出则截断。

    // 已知模块目录，用于从绝对路径相对化和推断 targetModule（来自真实 reactor 模块）。
    private static final Set<String> KNOWN_MODULE_DIRS = Set.of(
            "Teach-Brain-Entity", "Tech-Bain-Login", "Tech-Brain-Agent", "Tech-Brain-AOP",
            "Tech-Brain-Common", "Tech-Brain-Notes", "Tech-Brain-Tool", "Tech-Brain-Web", "tech-brain-web");
    // 关联缺陷编号提取：BUG-123 / bug#123 / issue-456 / #456。
    private static final Pattern BUG_ID_PATTERN = Pattern.compile("(?i)(bug[-#]?\\d+|issue[-#]?\\d+|#\\d+)");
    private static final Pattern ABSOLUTE_PATH_PATTERN = Pattern.compile("^[A-Za-z]:/.*"); // Windows 盘符绝对路径。
    private static final String TRUNCATION_NOTE = "（分析结果较长，已截断保存）"; // resultJson 截断时追加到 summary。

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(); // 仅用于解析 analyzeCode 统一结果，提取 summary/risks/testCases。

    private final ToolCallLogMapper toolCallLogMapper; // 复用 tool_call_log：回查来源 id、读取最近一条 analyzeCode 结果。

    public DevActionLogServiceImpl(ToolCallLogMapper toolCallLogMapper) { // 构造器注入 tool_call_log Mapper。
        this.toolCallLogMapper = toolCallLogMapper; // 保存 Mapper 引用。
    }

    // ===================== P6.1 统一保存入口 =====================

    @Override // 统一保存开发行为日志：兜底、补全 intent/summary/targetModule、脱敏路径、控制长度、落库，失败不抛异常。
    public DevActionLogSaveResult saveDevAction(DevActionLogCreateRequest request) {
        if (request == null) { // 入参为空无法保存。
            log.warn("[DevActionLog] skip saveDevAction, request is null"); // 只记录安全提示。
            return DevActionLogSaveResult.failed("开发行为日志入参为空，未保存。"); // 返回失败。
        }
        try {
            JsonNode resultNode = readTreeSafely(request.getResultJson()); // 解析统一结果（可能为空对象）。
            String analysisType = resolveAnalysisType(request, resultNode); // 统一 analysisType（大写，可空）。

            // 路径脱敏 + targetFile / targetPath 互相兜底（绝不保存服务器绝对路径）。
            String targetFile = sanitizeWorkspacePath(request.getTargetFile()); // 清洗 targetFile。
            String targetPath = sanitizeWorkspacePath(request.getTargetPath()); // 清洗 targetPath。
            if (isBlank(targetFile) && !isBlank(targetPath)) { // targetFile 为空但 targetPath 有值。
                targetFile = targetPath; // 用 targetPath 兜底 targetFile。
            }
            if (isBlank(targetPath) && !isBlank(targetFile)) { // targetPath 为空但 targetFile 有值。
                targetPath = targetFile; // 用 targetFile 兜底 targetPath（兼容 P5.9 历史字段）。
            }

            // result_json 长度控制（超过 1MB 截断，并在 summary 中标记）。
            boolean truncated = false; // 是否发生截断。
            String resultJson = request.getResultJson(); // 原始结果 JSON。
            if (resultJson != null && resultJson.length() > RESULT_JSON_MAX_LENGTH) { // 超过最大长度。
                resultJson = resultJson.substring(0, RESULT_JSON_MAX_LENGTH); // 截断。
                truncated = true; // 标记截断。
            }

            String displayTarget = resolveDisplayTarget(request, analysisType, targetFile); // 计算目标展示名。
            String intent = resolveIntent(request, analysisType, displayTarget); // 自动补 intent。
            String title = resolveTitle(request, analysisType, displayTarget); // 自动补 title。
            String summary = resolveSummary(request, analysisType, displayTarget, resultNode); // 自动补 summary。
            if (truncated) { // 截断时在摘要追加提示。
                summary = appendWithin(summary, TRUNCATION_NOTE, SUMMARY_MAX_LENGTH); // 安全追加截断说明。
            }

            LocalDateTime now = LocalDateTime.now(); // 创建与更新时间使用同一时间点。
            DevActionLog devLog = new DevActionLog(); // 构造开发行为日志记录。
            devLog.setUserId(request.getUserId()); // 用户 ID。
            devLog.setConversationId(request.getConversationId()); // 会话 ID。
            devLog.setTraceId(safeText(request.getTraceId(), TRACE_ID_MAX_LENGTH)); // 与 tool_call_log 一致的 traceId。
            devLog.setToolCallLogId(resolveToolCallLogId(request)); // 来源 tool_call_log.id（缺失按 traceId 回查）。
            devLog.setActionType(safeText(resolveActionType(request), ACTION_TYPE_MAX_LENGTH)); // 行为类型（默认 CODE_ANALYSIS）。
            devLog.setIntent(safeText(intent, INTENT_MAX_LENGTH)); // 行为意图（为什么做）。
            devLog.setResult(safeText(resolveResult(request), RESULT_MAX_LENGTH)); // 行为结果质量（默认 SUCCESS）。
            devLog.setAnalysisType(safeText(analysisType, ANALYSIS_TYPE_MAX_LENGTH)); // analysisType。
            devLog.setTargetType(safeText(resolveTargetType(request, targetFile), TARGET_TYPE_MAX_LENGTH)); // 目标类型。
            devLog.setTargetModule(safeText(resolveTargetModule(request, targetFile, targetPath, resultNode), TARGET_MODULE_MAX_LENGTH)); // 目标模块。
            devLog.setTargetFile(safeText(targetFile, TARGET_FILE_MAX_LENGTH)); // 目标文件（相对路径）。
            devLog.setTargetPath(safeText(targetPath, TARGET_PATH_MAX_LENGTH)); // 历史兼容文件路径。
            devLog.setClassName(safeText(request.getClassName(), CLASS_NAME_MAX_LENGTH)); // 类名。
            devLog.setMethodName(safeText(request.getMethodName(), METHOD_NAME_MAX_LENGTH)); // 方法名。
            devLog.setEndpoint(safeText(request.getEndpoint(), ENDPOINT_MAX_LENGTH)); // 接口路径。
            devLog.setToolName(safeText(request.getToolName(), TOOL_NAME_MAX_LENGTH)); // AI Tool 名称。
            devLog.setEventName(safeText(request.getEventName(), EVENT_NAME_MAX_LENGTH)); // SSE 事件名。
            devLog.setRelatedBugId(safeText(resolveRelatedBugId(request), RELATED_BUG_ID_MAX_LENGTH)); // 关联缺陷编号。
            devLog.setTitle(safeText(title, TITLE_MAX_LENGTH)); // 标题。
            devLog.setSummary(safeText(summary, SUMMARY_MAX_LENGTH)); // 自然语言摘要。
            devLog.setResultJson(resultJson); // 已控长度的结果 JSON。
            devLog.setStatus(safeText(resolveStatus(request), RESULT_MAX_LENGTH)); // 日志保存状态（默认 SUCCESS）。
            devLog.setErrorMsg(safeText(request.getErrorMsg(), ERROR_MSG_MAX_LENGTH)); // 失败原因（可空）。
            devLog.setCreatedAt(now); // 创建时间。
            devLog.setUpdatedAt(now); // 更新时间。

            save(devLog); // MyBatis-Plus 保存并回填自增 ID（自动覆盖含新增列在内的全部实体字段）。
            log.info("[DevActionLog] saveDevAction ok, id: {}, actionType: {}, analysisType: {}, traceId: {}",
                    devLog.getId(), devLog.getActionType(), analysisType, devLog.getTraceId()); // 不打印 result_json。
            return DevActionLogSaveResult.success(devLog.getId()); // 返回成功并携带日志 ID。
        } catch (Exception e) {
            log.warn("[DevActionLog] saveDevAction failed, traceId: {}, error: {}",
                    request.getTraceId(), e.getMessage(), e); // 保存失败只记录 warn，不影响主流程。
            return DevActionLogSaveResult.failed("保存开发日志失败，请稍后重试。"); // 异常按失败返回，不抛出。
        }
    }

    @Override // 记录一次代码分析行为（actionType=CODE_ANALYSIS），P5.9 复用此方法。
    public DevActionLogSaveResult recordCodeAnalysis(DevActionLogCreateRequest request) {
        if (request == null) { // 入参为空。
            return DevActionLogSaveResult.failed("开发行为日志入参为空，未保存。"); // 返回失败。
        }
        request.setActionType(DevActionType.CODE_ANALYSIS.name()); // 统一归类为代码分析行为。
        if (isBlank(request.getResult())) { // 结果质量未显式给出。
            request.setResult(DevActionResult.SUCCESS.name()); // 代码分析成功保存时默认 SUCCESS。
        }
        return saveDevAction(request); // 复用统一入口。
    }

    // ===================== P5.9 兼容入口 =====================

    @Override // P5.9 兼容：保存代码分析结果，返回日志 ID，失败返回 null，绝不抛出。
    public Long saveCodeAnalysisLog(DevActionLogSaveCommand command) {
        if (command == null || isBlank(command.getResultJson())) { // 保留 P5.9 行为：没有结果 JSON 不保存空日志。
            log.warn("[DevActionLog] skip save, command or resultJson is blank"); // 只记录安全提示。
            return null; // 返回 null，调用方按保存失败处理。
        }
        DevActionLogSaveResult result = recordCodeAnalysis(toRequest(command)); // 转为统一请求并走 saveDevAction。
        return result.isSaved() ? result.getDevLogId() : null; // 兼容原 Long 返回契约。
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
            DevActionLogSaveCommand command = buildCommandFromToolCallLog(lastLog, userId, conversationId, traceId); // 复原保存入参。
            Long devLogId = saveCodeAnalysisLog(command); // 复用统一保存逻辑。
            if (devLogId == null) { // 落库失败。
                return DevActionLogSaveResult.failed("保存开发日志失败，请稍后重试。"); // 返回失败。
            }
            return DevActionLogSaveResult.success(devLogId); // 返回成功并携带日志 ID。
        } catch (Exception e) {
            log.warn("[DevActionLog] save last analyzeCode result failed, conversationId: {}, userId: {}, error: {}",
                    conversationId, userId, e.getMessage(), e); // 失败只记录 warn。
            return DevActionLogSaveResult.failed("保存开发日志失败，请稍后重试。"); // 异常也按失败返回，不抛出。
        }
    }

    // ===================== P7-P14 预留语义入口（仅薄包装 saveDevAction，本步骤不接入真实业务）=====================

    @Override
    public DevActionLogSaveResult recordChangePlanGenerated(DevActionLogCreateRequest request) { // P7 预留。
        return saveWithType(request, DevActionType.CHANGE_PLAN_GENERATED); // 设置类型后复用统一入口。
    }

    @Override
    public DevActionLogSaveResult recordPatchGenerated(DevActionLogCreateRequest request) { // P8 预留。
        return saveWithType(request, DevActionType.PATCH_GENERATED); // 设置类型后复用统一入口。
    }

    @Override
    public DevActionLogSaveResult recordFileModified(DevActionLogCreateRequest request) { // P10 预留。
        return saveWithType(request, DevActionType.FILE_MODIFIED); // 设置类型后复用统一入口。
    }

    @Override
    public DevActionLogSaveResult recordCompileVerified(DevActionLogCreateRequest request) { // P11 预留。
        return saveWithType(request, DevActionType.COMPILE_VERIFIED); // 设置类型后复用统一入口。
    }

    @Override
    public DevActionLogSaveResult recordFrontendBuildVerified(DevActionLogCreateRequest request) { // 前端构建验证预留。
        return saveWithType(request, DevActionType.FRONTEND_BUILD_VERIFIED); // 设置类型后复用统一入口。
    }

    @Override
    public DevActionLogSaveResult recordReleaseConfirmed(DevActionLogCreateRequest request) { // 发布确认预留。
        return saveWithType(request, DevActionType.RELEASE_CONFIRMED); // 设置类型后复用统一入口。
    }

    @Override
    public DevActionLogSaveResult recordRollbackExecuted(DevActionLogCreateRequest request) { // P14 预留。
        return saveWithType(request, DevActionType.ROLLBACK_EXECUTED); // 设置类型后复用统一入口。
    }

    private DevActionLogSaveResult saveWithType(DevActionLogCreateRequest request, DevActionType type) { // 预留方法统一设置 actionType 再保存。
        if (request == null) { // 入参为空。
            return DevActionLogSaveResult.failed("开发行为日志入参为空，未保存。"); // 返回失败。
        }
        request.setActionType(type.name()); // 设置预留行为类型。
        return saveDevAction(request); // 复用统一入口。
    }

    // ===================== 入参转换 =====================

    private DevActionLogCreateRequest toRequest(DevActionLogSaveCommand command) { // 把 P5.9 narrow command 转为统一请求。
        DevActionLogCreateRequest request = new DevActionLogCreateRequest(); // 构造统一请求。
        request.setUserId(command.getUserId()); // 用户。
        request.setConversationId(command.getConversationId()); // 会话。
        request.setTraceId(command.getTraceId()); // traceId。
        request.setToolCallLogId(command.getToolCallLogId()); // 来源 tool_call_log.id。
        request.setAnalysisType(command.getAnalysisType()); // analysisType。
        request.setTargetType(command.getTargetType()); // 目标类型。
        request.setTargetPath(command.getTargetPath()); // 历史兼容文件路径（command 只有 targetPath，targetFile 由 Service 兜底）。
        request.setClassName(command.getClassName()); // 类名。
        request.setMethodName(command.getMethodName()); // 方法名。
        request.setEndpoint(command.getEndpoint()); // 接口路径。
        request.setToolName(command.getToolName()); // Tool 名称。
        request.setEventName(command.getEventName()); // SSE 事件名。
        request.setTitle(command.getDevLogTitle()); // 可选标题。
        request.setSummary(command.getDevLogSummary()); // 可选摘要。
        request.setResultJson(command.getResultJson()); // 统一结果 JSON。
        return request; // 返回统一请求。
    }

    // ===================== tool_call_log 关联 =====================

    private Long resolveToolCallLogId(DevActionLogCreateRequest request) { // 解析来源 tool_call_log.id：优先用入参，缺失时按 traceId 回查。
        if (request.getToolCallLogId() != null) { // 调用方已显式给出。
            return request.getToolCallLogId(); // 直接使用。
        }
        if (isBlank(request.getTraceId())) { // 没有 traceId 时无法回查。
            return null; // 返回空，tool_call_log_id 可为空。
        }
        try {
            LambdaQueryWrapper<ToolCallLog> wrapper = new LambdaQueryWrapper<>(); // 构造查询条件。
            wrapper.eq(ToolCallLog::getTraceId, safeText(request.getTraceId(), TRACE_ID_MAX_LENGTH)); // 按 traceId 精确匹配。
            wrapper.eq(ToolCallLog::getToolName, ANALYZE_CODE_TOOL_NAME); // 只取 analyzeCode 调用记录。
            wrapper.orderByDesc(ToolCallLog::getId); // 取同一 traceId 下最近一条。
            wrapper.last("LIMIT 1"); // 只取一条。
            ToolCallLog runningLog = toolCallLogMapper.selectOne(wrapper); // 查询当前轮 analyzeCode 日志。
            return runningLog == null ? null : runningLog.getId(); // 命中则返回其 id。
        } catch (Exception e) { // 回查失败不影响保存。
            log.debug("[DevActionLog] resolve tool_call_log_id by traceId failed"); // 仅 debug。
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
        command.setTraceId(isBlank(lastLog.getTraceId()) ? traceId : lastLog.getTraceId()); // 优先沿用原分析 traceId，保持链路一致。
        command.setToolCallLogId(lastLog.getId()); // 指向来源 tool_call_log.id。
        command.setAnalysisType(resultNode.path("analysisType").asText("")); // analysisType。
        command.setTargetPath(textOrNull(target.path("path"))); // workspace 相对路径。
        command.setClassName(textOrNull(target.path("className"))); // 类名。
        command.setMethodName(textOrNull(target.path("methodName"))); // 方法名。
        command.setEndpoint(textOrNull(target.path("endpoint"))); // 接口路径。
        command.setToolName(textOrNull(target.path("toolName"))); // Tool 名称。
        command.setEventName(textOrNull(target.path("eventName"))); // SSE 事件名。
        command.setResultJson(lastLog.getResultJson()); // 复用上一条分析的 result_json。
        return command; // 返回复原后的保存入参。
    }

    // ===================== 语义字段补全 =====================

    private String resolveAnalysisType(DevActionLogCreateRequest request, JsonNode resultNode) { // 统一 analysisType（大写），缺失时从结果回退。
        String type = request.getAnalysisType(); // 优先入参。
        if (isBlank(type)) { // 入参为空时从统一结果读取。
            type = resultNode.path("analysisType").asText(""); // 读取结果中的 analysisType。
        }
        return isBlank(type) ? null : type.trim().toUpperCase(Locale.ROOT); // 统一大写，可空。
    }

    private String resolveActionType(DevActionLogCreateRequest request) { // 行为类型：显式优先，缺失默认 CODE_ANALYSIS。
        return DevActionType.fromName(request.getActionType()).name(); // 经枚举校验，非法/空兜底为 CODE_ANALYSIS。
    }

    private String resolveResult(DevActionLogCreateRequest request) { // 行为结果质量：显式优先，缺失默认 SUCCESS。
        return DevActionResult.fromName(request.getResult()).name(); // 经枚举校验，非法/空兜底为 SUCCESS。
    }

    private String resolveStatus(DevActionLogCreateRequest request) { // 日志保存状态：显式优先，缺失默认 SUCCESS。
        return DevActionStatus.fromName(request.getStatus()).name(); // 经枚举校验，非法/空兜底为 SUCCESS。
    }

    private String resolveTargetType(DevActionLogCreateRequest request, String targetFile) { // 推断目标类型（DevTargetType）。
        if (!isBlank(request.getTargetType())) { // 调用方已显式给出。
            return DevTargetType.fromName(request.getTargetType()).name(); // 经枚举校验。
        }
        if (!isBlank(request.getEndpoint())) { // 有接口路径。
            return DevTargetType.ENDPOINT.name(); // 接口目标。
        }
        if (!isBlank(request.getToolName())) { // 有 Tool 名称。
            return DevTargetType.TOOL.name(); // Tool 目标。
        }
        if (!isBlank(request.getEventName())) { // 有 SSE 事件名。
            return DevTargetType.EVENT.name(); // 事件目标。
        }
        if (!isBlank(request.getClassName())) { // 有类名。
            return DevTargetType.CLASS.name(); // 类目标。
        }
        if (!isBlank(request.getMethodName())) { // 仅有方法名。
            return DevTargetType.METHOD.name(); // 方法目标。
        }
        if (!isBlank(targetFile)) { // 仅有文件路径。
            return DevTargetType.FILE.name(); // 文件目标。
        }
        if (!isBlank(request.getTargetModule())) { // 仅有模块。
            return DevTargetType.MODULE.name(); // 模块目标。
        }
        return DevTargetType.AUTO.name(); // 无法判断。
    }

    private String resolveDisplayTarget(DevActionLogCreateRequest request, String analysisType, String targetFile) { // 计算标题/意图/摘要中的目标展示名。
        if ("CONTROLLER_SERVICE".equals(analysisType) && !isBlank(request.getEndpoint())) { // 接口链路优先 endpoint。
            return request.getEndpoint().trim(); // 返回接口路径。
        }
        if ("TOOL_SERVICE".equals(analysisType) && !isBlank(request.getToolName())) { // Tool 链路优先 toolName。
            return request.getToolName().trim(); // 返回 Tool 名称。
        }
        if ("SSE_EVENT_CHAIN".equals(analysisType) && !isBlank(request.getEventName())) { // SSE 链路优先事件名。
            return request.getEventName().trim(); // 返回事件名。
        }
        if (!isBlank(request.getClassName())) { // 其它类型优先类名。
            return request.getClassName().trim(); // 返回类名。
        }
        if (!isBlank(request.getToolName())) { // 退而用 Tool 名称。
            return request.getToolName().trim(); // 返回 Tool 名称。
        }
        if (!isBlank(request.getEndpoint())) { // 退而用接口路径。
            return request.getEndpoint().trim(); // 返回接口路径。
        }
        if (!isBlank(request.getEventName())) { // 退而用事件名。
            return request.getEventName().trim(); // 返回事件名。
        }
        if (!isBlank(request.getMethodName())) { // 退而用方法名。
            return request.getMethodName().trim(); // 返回方法名。
        }
        if (!isBlank(targetFile)) { // 最后用文件名（取相对路径末段）。
            return fileNameOf(targetFile.trim()); // 返回文件名。
        }
        return "项目代码"; // 无目标时的兜底展示名。
    }

    private String resolveIntent(DevActionLogCreateRequest request, String analysisType, String target) { // 自动补 intent（为什么做）。
        if (!isBlank(request.getIntent())) { // 调用方已显式给出。
            return request.getIntent().trim(); // 直接使用。
        }
        switch (isBlank(analysisType) ? "" : analysisType) { // 按 analysisType 抽象出可检索的意图。
            case "RISK":
                return "分析 " + target + " 的潜在风险，为后续修复和测试提供依据。"; // 风险意图。
            case "TEST_STEPS":
                return "生成 " + target + " 的测试步骤，确保核心行为可验证和可回归。"; // 测试意图。
            case "EXPLANATION":
                return "说明 " + target + " 的代码流程，帮助理解接口实现和调用链。"; // 说明意图。
            case "CALL_CHAIN":
                return "分析 " + target + " 的调用链，明确依赖关系和执行路径。"; // 调用链意图。
            case "STRUCTURE":
                return "分析 " + target + " 的代码结构，梳理类、方法与字段。"; // 结构意图。
            case "CONTROLLER_SERVICE":
                return "分析 " + target + " 接口到 Service 的调用链路，理清后端处理流程。"; // 接口链路意图。
            case "TOOL_SERVICE":
                return "分析 " + target + " 这个 Tool 到业务组件的调用链路，理清工具实现。"; // Tool 链路意图。
            case "SSE_EVENT_CHAIN":
                return "分析 " + target + " 的前后端 SSE 事件链路，理清流式交互。"; // SSE 意图。
            default:
                // 无法按类型生成时用 title 或 summary 兜底，避免 intent 为空。
                if (!isBlank(request.getTitle())) {
                    return request.getTitle().trim(); // 用标题兜底。
                }
                if (!isBlank(request.getSummary())) {
                    return request.getSummary().trim(); // 用摘要兜底。
                }
                return "记录一次针对 " + target + " 的开发行为。"; // 最终兜底。
        }
    }

    private String resolveTitle(DevActionLogCreateRequest request, String analysisType, String target) { // title 为空时自动生成。
        if (!isBlank(request.getTitle())) { // 用户显式指定标题。
            return request.getTitle().trim(); // 直接使用。
        }
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
                return "开发行为：" + target; // 非分析类行为兜底标题。
        }
    }

    private String resolveSummary(DevActionLogCreateRequest request, String analysisType, String target, JsonNode resultNode) { // 自动生成自然语言摘要（P17 向量化核心）。
        if (!isBlank(request.getSummary())) { // 用户显式指定摘要。
            return request.getSummary().trim(); // 直接使用。
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
        if (risks.isArray() || safePoints.isArray() || inner.has("riskCount")) { // 3. 风险类结果（含 riskCount 字段）。
            int riskCount = inner.has("riskCount") ? inner.path("riskCount").asInt(0)
                    : (risks.isArray() ? risks.size() : 0); // 风险点数量优先取 riskCount。
            int safeCount = safePoints.isArray() ? safePoints.size() : 0; // 保护点数量。
            return "完成对 " + target + " 的风险分析，发现 " + riskCount + " 个风险点，" + safeCount + " 个已有保护点。"; // 风险摘要。
        }
        JsonNode testCases = inner.path("testCases"); // 测试用例数组。
        if (testCases.isArray() && testCases.size() > 0) { // 4. 测试步骤结果。
            return "完成对 " + target + " 的测试步骤生成，生成 " + testCases.size() + " 个测试用例，包含日志检查和回归检查。"; // 测试摘要。
        }
        if (!isBlank(inner.path("explanationType").asText(""))) { // 5. 代码说明结果。
            return "完成对 " + target + " 的代码说明生成，梳理了职责、主流程与关键方法。"; // 说明摘要。
        }
        String typeText = isBlank(analysisType) ? "代码" : analysisType; // 6. 兜底文案使用 analysisType。
        return "完成一次针对 " + target + " 的 " + typeText + " 类型代码分析。"; // 兜底摘要。
    }

    private String resolveTargetModule(DevActionLogCreateRequest request,
                                       String targetFile,
                                       String targetPath,
                                       JsonNode resultNode) { // 推断目标模块（如 Tech-Brain-Agent）。
        if (!isBlank(request.getTargetModule())) { // 调用方已显式给出。
            return request.getTargetModule().trim(); // 直接使用。
        }
        String module = moduleFromPath(targetFile); // 优先从 targetFile 推断。
        if (module == null) { // 再从 targetPath 推断。
            module = moduleFromPath(targetPath); // 从历史兼容路径推断。
        }
        if (module == null) { // 再从统一结果里的真实路径推断。
            module = moduleFromPath(textOrNull(resultNode.path("target").path("path"))); // target.path。
        }
        if (module == null) { // 再尝试结果顶层 path（兼容部分分析器）。
            module = moduleFromPath(textOrNull(resultNode.path("path"))); // 顶层 path。
        }
        return module; // 无法判断则返回 null（置空）。
    }

    private String moduleFromPath(String path) { // 从 workspace 相对路径取首段模块目录。
        if (isBlank(path)) { // 空路径。
            return null; // 无模块。
        }
        String p = path.trim().replace('\\', '/'); // 统一分隔符。
        while (p.startsWith("./")) { // 去掉前导 ./。
            p = p.substring(2); // 截断。
        }
        if (p.startsWith("/")) { // 去掉前导 /。
            p = p.substring(1); // 截断。
        }
        int slash = p.indexOf('/'); // 首段分隔位置。
        String first = slash >= 0 ? p.substring(0, slash) : p; // 取首段。
        return KNOWN_MODULE_DIRS.contains(first) ? first : null; // 命中已知模块目录才作为 targetModule。
    }

    // ===================== 路径脱敏 / 关联缺陷 =====================

    private String sanitizeWorkspacePath(String raw) { // 清洗路径：绝不保存服务器绝对路径，尽量转为 workspace 相对路径。
        if (isBlank(raw)) { // 空路径。
            return null; // 返回 null。
        }
        String p = raw.trim().replace('\\', '/'); // 统一分隔符。
        boolean absolute = ABSOLUTE_PATH_PATTERN.matcher(p).matches() || p.startsWith("/"); // 盘符或根路径视为绝对路径。
        if (!absolute) { // 已是相对路径。
            while (p.startsWith("./")) { // 去掉前导 ./。
                p = p.substring(2); // 截断。
            }
            return p; // 返回相对路径。
        }
        String[] segs = p.split("/"); // 拆分路径段。
        for (int i = 0; i < segs.length; i++) { // 从已知模块目录段开始相对化。
            if (KNOWN_MODULE_DIRS.contains(segs[i])) { // 命中模块目录。
                return String.join("/", Arrays.copyOfRange(segs, i, segs.length)); // 返回从模块目录起的相对路径。
            }
        }
        return null; // 无法相对化的绝对路径一律脱敏置空，不入库。
    }

    private String resolveRelatedBugId(DevActionLogCreateRequest request) { // 解析关联缺陷编号：显式优先，否则从文本提取。
        if (!isBlank(request.getRelatedBugId())) { // 调用方已显式给出。
            return request.getRelatedBugId().trim(); // 直接使用。
        }
        String hay = joinNonBlank(request.getTitle(), request.getIntent(), request.getSummary()); // 在标题/意图/摘要中找缺陷编号。
        if (isBlank(hay)) { // 无可扫描文本。
            return null; // 返回空。
        }
        Matcher matcher = BUG_ID_PATTERN.matcher(hay); // 匹配 BUG-xxx / issue-xxx / #xxx。
        return matcher.find() ? matcher.group(1) : null; // 命中返回首个，否则空。
    }

    // ===================== 通用工具 =====================

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

    private String joinNonBlank(String... parts) { // 拼接非空文本，用于扫描缺陷编号。
        StringBuilder sb = new StringBuilder(); // 结果缓冲。
        if (parts != null) { // 有候选。
            for (String part : parts) { // 遍历候选文本。
                if (!isBlank(part)) { // 跳过空白。
                    sb.append(part).append(' '); // 追加并以空格分隔。
                }
            }
        }
        return sb.toString().trim(); // 返回拼接结果。
    }

    private String appendWithin(String base, String suffix, int maxLength) { // 在不超长前提下安全追加后缀。
        String prefix = base == null ? "" : base; // 空基串按空处理。
        if (suffix == null || suffix.isEmpty()) { // 无后缀。
            return prefix; // 返回原值。
        }
        if (prefix.length() + suffix.length() <= maxLength) { // 追加后不超长。
            return prefix + suffix; // 直接追加。
        }
        int keep = Math.max(0, maxLength - suffix.length()); // 为后缀预留空间。
        return prefix.substring(0, Math.min(prefix.length(), keep)) + suffix; // 截断基串后追加后缀。
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

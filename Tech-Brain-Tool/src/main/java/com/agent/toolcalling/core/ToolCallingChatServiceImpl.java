package com.agent.toolcalling.core;

import com.agent.toolcalling.client.DeepSeekClient;
import com.agent.toolcalling.client.DeepSeekStreamCallback;
import com.agent.toolcalling.config.DeepSeekProperties;
import com.agent.toolcalling.context.ChatAttachedFileContext;
import com.agent.toolcalling.context.ToolCallingContextHolder;
import com.agent.toolcalling.context.ToolCallingRequestContext;
import com.agent.toolcalling.log.ToolCallLogRecorder;
import com.agent.toolcalling.registry.ToolRegistry;
import com.agent.toolcalling.spi.AiTool;
import com.agent.toolcalling.summary.SummaryTypeConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool Calling 聊天编排器。
 *
 * <p>本类只负责保留型检索/上下文工具：ragSearch、summarizeArticle、readFile，以及未来注册到 Spring 的非编码类工具。
 * 项目源码读取、代码搜索、代码分析、生成修改方案和生成 diff 的自建编码智能体能力已经移除。</p>
 */
@Slf4j
@Service
public class ToolCallingChatServiceImpl implements ToolCallingChatService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final int HISTORY_MESSAGE_LIMIT = 6;
    private static final int HISTORY_CONTENT_MAX_LENGTH = 1200;
    private static final int MEMORY_SUMMARY_MAX_LENGTH = 1500;
    private static final int TOOL_RESULT_MAX_LENGTH = 60_000;

    private static final String RAG_SEARCH_TOOL_NAME = "ragSearch";
    private static final String SUMMARIZE_ARTICLE_TOOL_NAME = "summarizeArticle";
    private static final String READ_FILE_TOOL_NAME = "readFile";
    private static final String APPLY_PATCH_TOOL_NAME = "applyPatch";
    private static final String RUN_MAVEN_COMPILE_TOOL_NAME = "runMavenCompile";
    private static final String ARTICLE_SUMMARY_RESULT_TYPE = "article_summary";
    private static final String SUMMARY_RESULT_EVENT_NAME = "summary_result";

    private static final String CALL_SOURCE_FORCE_ROUTE = "FORCE_ROUTE";
    private static final String CALL_SOURCE_MODEL_TOOL_CALL = "MODEL_TOOL_CALL";
    private static final String ROUTE_REASON_FORCE_RAG_SEARCH = "force ragSearch";
    private static final String ROUTE_REASON_FORCE_SUMMARIZE_ARTICLE = "force summarizeArticle";
    private static final String ROUTE_REASON_FORCE_READ_FILE = "force readFile";
    private static final String ROUTE_REASON_FORCE_APPLY_PATCH = "force applyPatch";
    private static final String ROUTE_REASON_FORCE_RUN_MAVEN_COMPILE = "force runMavenCompile";
    private static final String ROUTE_REASON_MODEL_TOOL_CALL = "model tool_call";

    private static final String TOOL_TYPE_RAG = "RAG";
    private static final String TOOL_TYPE_SUMMARY = "SUMMARY";
    private static final String TOOL_TYPE_FILE = "FILE";
    private static final String TOOL_TYPE_SELF_DEV = "SELF_DEV";
    private static final String TOOL_TYPE_OTHER = "OTHER";

    private static final Pattern ARTICLE_ID_PATTERN = Pattern.compile("(?:articleId|文章|笔记|ID|id)\\s*[:：#]??\\s*(\\d+)");
    private static final Pattern FILE_ID_PATTERN = Pattern.compile("(?:fileId|文件|附件|ID|id)\\s*[:：#]??\\s*(\\d+)");
    private static final Pattern WORKSPACE_ID_PATTERN = Pattern.compile("(?i)workspaceId\\s*[:=]\\s*([A-Za-z0-9._-]+)");
    private static final Pattern PATCH_FILE_PATH_PATTERN = Pattern.compile("(?i)patchFilePath\\s*[:=]\\s*([^\\s]+)");
    private static final Pattern MODULE_PATTERN = Pattern.compile("(?i)(?:module|模块)\\s*[:=：]\\s*([A-Za-z0-9._-]+)");
    private static final Pattern TIMEOUT_SECONDS_PATTERN = Pattern.compile("(?i)(?:timeoutSeconds|timeout|超时)\\s*[:=：]\\s*(\\d+)");
    private static final Pattern PROFILES_PATTERN = Pattern.compile("(?i)(?:profiles|profile|配置)\\s*[:=：]\\s*([A-Za-z0-9._,-]+)");

    private static final String CONTEXT_PRIORITY_PROMPT = "\n\n上下文优先级从高到低："
            + "\n1. 当前用户输入 currentMessage。"
            + "\n2. 本轮工具结果 toolResult。"
            + "\n3. 最近短期历史 historyMessages。"
            + "\n4. 会话长期记忆 memorySummary。"
            + "\n5. 模型自身知识。"
            + "\n如果本轮调用了工具，必须优先基于工具结果回答。没有调用工具时，不要声称已经检索知识库或读取文件。";

    private static final String SYSTEM_PROMPT = "你是 Tech-Brain 项目的 AI 助手。"
            + "\n当前可用工具只包含检索和上下文能力："
            + "\n1. ragSearch：检索用户私人知识库、笔记、文章和项目资料。"
            + "\n2. summarizeArticle：按 articleId 或最近检索到的文章/笔记生成总结。"
            + "\n3. readFile：读取当前用户已上传的文本/代码类文件。"
            + "\n不要尝试读取服务器项目目录、搜索项目源码、分析项目调用链、生成修改方案、生成 patch 或修改代码。"
            + "\n如果用户提出开发改代码需求，应提示其使用 Claude Code 开发执行入口。"
            + "\n对于当前会话附件，未调用 readFile 前不要假装看过文件正文。"
            + CONTEXT_PRIORITY_PROMPT;

    private static final String NO_TOOL_SYSTEM_PROMPT = "你是 Tech-Brain 项目的 AI 助手。"
            + "\n本轮没有执行工具，请基于当前问题、会话历史和长期记忆正常回答。"
            + "\n不要声称已经检索知识库、读取文件或分析项目源码。"
            + CONTEXT_PRIORITY_PROMPT;

    private static final String FORCED_RAG_SYSTEM_PROMPT = "你是 Tech-Brain 项目的 AI 助手。"
            + "\n后端已经执行 ragSearch 工具。必须优先基于 ragSearch 返回的知识库内容回答。"
            + "\n如果工具结果说明没有检索到相关内容，只能说明本次知识库检索未找到直接相关内容，不要编造知识库结果。"
            + CONTEXT_PRIORITY_PROMPT;

    private static final String FORCED_READ_FILE_SYSTEM_PROMPT = "你是 Tech-Brain 项目的 AI 助手。"
            + "\n后端已经执行 readFile 工具。必须基于工具返回的文件内容或失败原因回答。"
            + "\n如果 success=true，基于 content 字段分析；如果 truncated=true，需要说明内容已截断。"
            + "\n如果 success=false，只说明工具返回的失败原因，不要假装读取成功。"
            + CONTEXT_PRIORITY_PROMPT;

    private final DeepSeekClient deepSeekClient;
    private final DeepSeekProperties deepSeekProperties;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolCallingChatServiceImpl(DeepSeekClient deepSeekClient,
                                      DeepSeekProperties deepSeekProperties,
                                      ToolRegistry toolRegistry) {
        this.deepSeekClient = deepSeekClient;
        this.deepSeekProperties = deepSeekProperties;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public void chatStream(String message, ToolCallingStreamCallback callback) {
        chatStream(message, "", Collections.emptyList(), null, callback);
    }

    @Override
    public void chatStream(String currentMessage,
                           List<ToolChatHistoryMessage> historyMessages,
                           ToolCallingStreamCallback callback) {
        chatStream(currentMessage, "", historyMessages, null, callback);
    }

    @Override
    public void chatStream(String currentMessage,
                           String memorySummary,
                           List<ToolChatHistoryMessage> historyMessages,
                           ToolCallingStreamCallback callback) {
        chatStream(currentMessage, memorySummary, historyMessages, null, callback);
    }

    @Override
    public void chatStream(String currentMessage,
                           String memorySummary,
                           List<ToolChatHistoryMessage> historyMessages,
                           ToolCallingRequestContext requestContext,
                           ToolCallingStreamCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("ToolCallingStreamCallback 不能为空");
        }
        String userMessage = currentMessage == null ? "" : currentMessage.trim();
        String safeMemorySummary = limitText(memorySummary, MEMORY_SUMMARY_MAX_LENGTH);
        List<ToolChatHistoryMessage> safeHistory = normalizeHistory(historyMessages);
        try {
            if (requestContext != null && !requestContext.isToolsEnabled()) {
                streamModel(buildNoToolStreamRequest(userMessage, safeMemorySummary, safeHistory, requestContext), callback);
                return;
            }

            if (shouldForceRunMavenCompile(userMessage, requestContext)) {
                streamWithForcedRunMavenCompile(userMessage, safeMemorySummary, safeHistory, requestContext, callback);
                return;
            }

            if (shouldForceApplyPatch(userMessage, requestContext)) {
                streamWithForcedApplyPatch(userMessage, safeMemorySummary, safeHistory, requestContext, callback);
                return;
            }

            if (shouldForceSummarizeArticle(userMessage, requestContext)) {
                streamWithForcedSummarizeArticle(userMessage, requestContext, callback);
                return;
            }

            if (shouldForceReadFile(userMessage, requestContext)) {
                streamWithForcedReadFile(userMessage, safeMemorySummary, safeHistory, requestContext, callback);
                return;
            }

            if (shouldForceRagSearch(userMessage, requestContext)) {
                streamWithForcedRagSearch(userMessage, safeMemorySummary, safeHistory, requestContext, callback);
                return;
            }

            JsonNode firstResponse = deepSeekClient.chatCompletions(buildFirstRequest(userMessage, requestContext));
            JsonNode firstMessage = firstResponse.path("choices").path(0).path("message");
            JsonNode toolCall = firstMessage.path("tool_calls").path(0);
            if (toolCall.isMissingNode() || toolCall.isNull()) {
                streamModel(buildNoToolStreamRequest(userMessage, safeMemorySummary, safeHistory, requestContext), callback);
                return;
            }

            String toolName = toolCall.path("function").path("name").asText("");
            JsonNode arguments = parseArguments(toolCall.path("function").path("arguments").asText("{}"));
            if (!isAllowedToolName(toolName)) {
                log.warn("[ToolChatStream] ignore unavailable or removed tool: {}", toolName);
                streamModel(buildNoToolStreamRequest(userMessage, safeMemorySummary, safeHistory, requestContext), callback);
                return;
            }

            String toolResult = executeToolForStream(toolName, arguments, requestContext);
            if (emitArticleSummaryIfNeeded(toolResult, callback)) {
                return;
            }
            ObjectNode answerRequest = buildToolAnswerRequest(userMessage, safeMemorySummary, safeHistory, toolName, arguments, toolResult, requestContext);
            streamModel(answerRequest, callback);
        } catch (Exception e) {
            log.error("[ToolChatStream] chatStream failed", e);
            callback.onError(e);
        }
    }

    private void streamWithForcedRagSearch(String userMessage,
                                          String memorySummary,
                                          List<ToolChatHistoryMessage> historyMessages,
                                          ToolCallingRequestContext requestContext,
                                          ToolCallingStreamCallback callback) {
        AiTool ragTool = toolRegistry.getTool(RAG_SEARCH_TOOL_NAME);
        if (ragTool == null) {
            callback.onError(new IllegalStateException("ragSearch 工具未注册"));
            return;
        }
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("query", userMessage);
        String toolResult = executeToolWithLog(ragTool, arguments, requestContext, CALL_SOURCE_FORCE_ROUTE, ROUTE_REASON_FORCE_RAG_SEARCH);
        streamModel(buildForcedToolAnswerRequest(FORCED_RAG_SYSTEM_PROMPT, userMessage, memorySummary, historyMessages,
                RAG_SEARCH_TOOL_NAME, arguments, toolResult, requestContext), callback);
    }

    private void streamWithForcedApplyPatch(String userMessage,
                                            String memorySummary,
                                            List<ToolChatHistoryMessage> historyMessages,
                                            ToolCallingRequestContext requestContext,
                                            ToolCallingStreamCallback callback) {
        AiTool applyPatchTool = toolRegistry.getTool(APPLY_PATCH_TOOL_NAME); // 获取 applyPatch 工具。
        if (applyPatchTool == null) {
            callback.onError(new IllegalStateException("applyPatch 工具未注册")); // 未注册时返回错误。
            return;
        }
        ObjectNode arguments = buildForcedApplyPatchArguments(userMessage); // 从用户消息提取明确参数。
        String toolResult = executeToolWithLog(applyPatchTool, arguments, requestContext, CALL_SOURCE_FORCE_ROUTE, ROUTE_REASON_FORCE_APPLY_PATCH); // 执行并进入 tool_call_log。
        streamModel(buildForcedToolAnswerRequest("你是 Tech-Brain 项目的 AI 助手。本轮已经执行 applyPatch 工具。必须基于工具 JSON 结果回答；成功时只提示可进入 P11 编译验证，不要声称已经编译或发布。"
                        + CONTEXT_PRIORITY_PROMPT,
                userMessage, memorySummary, historyMessages, APPLY_PATCH_TOOL_NAME, arguments, toolResult, requestContext), callback); // 把工具结果交给模型生成最终回答。
    }

    private void streamWithForcedRunMavenCompile(String userMessage,
                                                 String memorySummary,
                                                 List<ToolChatHistoryMessage> historyMessages,
                                                 ToolCallingRequestContext requestContext,
                                                 ToolCallingStreamCallback callback) {
        AiTool compileTool = toolRegistry.getTool(RUN_MAVEN_COMPILE_TOOL_NAME); // 获取 runMavenCompile 工具。
        if (compileTool == null) {
            callback.onError(new IllegalStateException("runMavenCompile 工具未注册")); // 未注册时返回错误。
            return;
        }
        ObjectNode arguments = buildForcedRunMavenCompileArguments(userMessage); // 从用户消息提取明确编译参数。
        String toolResult = executeToolWithLog(compileTool, arguments, requestContext, CALL_SOURCE_FORCE_ROUTE, ROUTE_REASON_FORCE_RUN_MAVEN_COMPILE); // 执行并进入 tool_call_log。
        streamModel(buildForcedToolAnswerRequest("你是 Tech-Brain 项目的 AI 助手。本轮已经执行 runMavenCompile 工具。必须基于工具 JSON 结果回答；成功时说明可进入 P12 前端构建验证，或如果本次没有前端改动可进入 P13 候选确认；失败时只总结错误摘要，不要自动修复代码、不要调用 Claude Code、不要生成或应用 patch、不要发布。"
                        + CONTEXT_PRIORITY_PROMPT,
                userMessage, memorySummary, historyMessages, RUN_MAVEN_COMPILE_TOOL_NAME, arguments, toolResult, requestContext), callback); // 把工具结果交给模型生成最终回答。
    }

    private void streamWithForcedReadFile(String userMessage,
                                          String memorySummary,
                                          List<ToolChatHistoryMessage> historyMessages,
                                          ToolCallingRequestContext requestContext,
                                          ToolCallingStreamCallback callback) {
        ForceReadFileTarget target = resolveReadFileTarget(userMessage, requestContext);
        if (target == null || target.fileId() == null) {
            callback.onToken("请指定要读取的文件，或在本轮只选择一个文本/代码附件。");
            callback.onComplete();
            return;
        }
        AiTool readFileTool = toolRegistry.getTool(READ_FILE_TOOL_NAME);
        if (readFileTool == null) {
            callback.onError(new IllegalStateException("readFile 工具未注册"));
            return;
        }
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("fileId", target.fileId());
        arguments.put("maxChars", 12000);
        String toolResult = executeToolWithLog(readFileTool, arguments, requestContext, CALL_SOURCE_FORCE_ROUTE, ROUTE_REASON_FORCE_READ_FILE);
        streamModel(buildForcedToolAnswerRequest(FORCED_READ_FILE_SYSTEM_PROMPT, userMessage, memorySummary, historyMessages,
                READ_FILE_TOOL_NAME, arguments, toolResult, requestContext), callback);
    }

    private void streamWithForcedSummarizeArticle(String userMessage,
                                                  ToolCallingRequestContext requestContext,
                                                  ToolCallingStreamCallback callback) {
        AiTool summarizeTool = toolRegistry.getTool(SUMMARIZE_ARTICLE_TOOL_NAME);
        if (summarizeTool == null) {
            callback.onError(new IllegalStateException("summarizeArticle 工具未注册"));
            return;
        }
        ObjectNode arguments = objectMapper.createObjectNode();
        Long articleId = extractLong(ARTICLE_ID_PATTERN, userMessage);
        if (articleId != null) {
            arguments.put("articleId", articleId);
        }
        arguments.put("summaryType", resolveSummaryType(userMessage));
        String toolResult = executeToolWithLog(summarizeTool, arguments, requestContext, CALL_SOURCE_FORCE_ROUTE, ROUTE_REASON_FORCE_SUMMARIZE_ARTICLE);
        if (!emitArticleSummaryIfNeeded(toolResult, callback)) {
            callback.onToken(readChatMessageFromToolResult(toolResult));
            callback.onComplete();
        }
    }

    private ObjectNode buildFirstRequest(String userMessage, ToolCallingRequestContext requestContext) {
        ObjectNode request = buildBaseRequest();
        ArrayNode messages = request.putArray("messages");
        addMessage(messages, "system", SYSTEM_PROMPT + buildAttachedFilesPrompt(requestContext));
        addMessage(messages, ROLE_USER, userMessage);
        if (!toolRegistry.getAllTools().isEmpty()) {
            request.set("tools", toolRegistry.buildToolsJson());
            request.put("tool_choice", "auto");
        }
        return request;
    }

    private ObjectNode buildNoToolStreamRequest(String userMessage,
                                                String memorySummary,
                                                List<ToolChatHistoryMessage> historyMessages,
                                                ToolCallingRequestContext requestContext) {
        ObjectNode request = buildBaseRequest();
        ArrayNode messages = request.putArray("messages");
        addMessage(messages, "system", NO_TOOL_SYSTEM_PROMPT + buildAttachedFilesPrompt(requestContext));
        appendMemoryAndHistory(messages, memorySummary, historyMessages);
        addMessage(messages, ROLE_USER, userMessage);
        return request;
    }

    private ObjectNode buildForcedToolAnswerRequest(String systemPrompt,
                                                    String userMessage,
                                                    String memorySummary,
                                                    List<ToolChatHistoryMessage> historyMessages,
                                                    String toolName,
                                                    JsonNode arguments,
                                                    String toolResult,
                                                    ToolCallingRequestContext requestContext) {
        ObjectNode request = buildBaseRequest();
        ArrayNode messages = request.putArray("messages");
        addMessage(messages, "system", systemPrompt + buildAttachedFilesPrompt(requestContext));
        appendMemoryAndHistory(messages, memorySummary, historyMessages);
        addMessage(messages, ROLE_USER, userMessage);
        addMessage(messages, ROLE_USER, buildToolResultPrompt(toolName, arguments, toolResult));
        return request;
    }

    private ObjectNode buildToolAnswerRequest(String userMessage,
                                              String memorySummary,
                                              List<ToolChatHistoryMessage> historyMessages,
                                              String toolName,
                                              JsonNode arguments,
                                              String toolResult,
                                              ToolCallingRequestContext requestContext) {
        return buildForcedToolAnswerRequest("你是 Tech-Brain 项目的 AI 助手。本轮已经执行工具，请基于工具结果回答。"
                        + CONTEXT_PRIORITY_PROMPT,
                userMessage, memorySummary, historyMessages, toolName, arguments, toolResult, requestContext);
    }

    private ObjectNode buildBaseRequest() {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", deepSeekProperties.getModelName());
        if (deepSeekProperties.getTemperature() != null) {
            request.put("temperature", deepSeekProperties.getTemperature());
        }
        ObjectNode thinking = objectMapper.createObjectNode();
        thinking.put("type", "disabled");
        request.set("thinking", thinking);
        return request;
    }

    private void streamModel(ObjectNode request, ToolCallingStreamCallback callback) {
        deepSeekClient.streamChatCompletions(request, new DeepSeekStreamCallback() {
            @Override
            public void onToken(String token) {
                callback.onToken(token);
            }

            @Override
            public void onComplete() {
                callback.onComplete();
            }

            @Override
            public void onError(Throwable error) {
                callback.onError(error);
            }
        });
    }

    private String executeToolForStream(String toolName, JsonNode argumentsNode, ToolCallingRequestContext requestContext) {
        AiTool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("工具未注册: " + toolName);
        }
        return executeToolWithLog(tool, argumentsNode, requestContext, CALL_SOURCE_MODEL_TOOL_CALL, ROUTE_REASON_MODEL_TOOL_CALL);
    }

    private String executeToolWithLog(AiTool tool,
                                      JsonNode argumentsNode,
                                      ToolCallingRequestContext requestContext,
                                      String callSource,
                                      String routeReason) {
        String toolName = tool.name();
        String argumentsJson = serializeArgumentsForLog(toolName, argumentsNode);
        Long logId = createRunningToolLog(requestContext, toolName, toolTypeOf(toolName), callSource, routeReason, argumentsJson);
        long startTime = System.currentTimeMillis();
        try {
            String toolResult = executeToolWithContext(tool, argumentsNode, requestContext);
            markToolLogSuccess(requestContext, logId, toolResult, System.currentTimeMillis() - startTime);
            return toolResult;
        } catch (Exception e) {
            markToolLogFailed(requestContext, logId, e.getMessage(), System.currentTimeMillis() - startTime);
            throw e;
        }
    }

    private String executeToolWithContext(AiTool tool, JsonNode argumentsNode, ToolCallingRequestContext requestContext) {
        ToolCallingContextHolder.set(requestContext);
        try {
            return tool.execute(argumentsNode);
        } finally {
            ToolCallingContextHolder.clear();
        }
    }

    private Long createRunningToolLog(ToolCallingRequestContext context,
                                      String toolName,
                                      String toolType,
                                      String callSource,
                                      String routeReason,
                                      String argumentsJson) {
        ToolCallLogRecorder recorder = context == null ? null : context.getToolCallLogRecorder();
        if (recorder == null) {
            return null;
        }
        try {
            return recorder.createRunningLog(context.getTraceId(), context.getConversationId(), context.getUserId(),
                    context.getCurrentMessage(), toolName, toolType, callSource, routeReason, argumentsJson);
        } catch (Exception e) {
            log.warn("[ToolCallLog] create log failed, toolName: {}", toolName, e);
            return null;
        }
    }

    private void markToolLogSuccess(ToolCallingRequestContext context, Long logId, String resultJson, long durationMs) {
        ToolCallLogRecorder recorder = context == null ? null : context.getToolCallLogRecorder();
        if (recorder == null || logId == null) {
            return;
        }
        try {
            recorder.markSuccess(logId, resultJson, durationMs);
        } catch (Exception e) {
            log.warn("[ToolCallLog] mark success failed, id: {}", logId, e);
        }
    }

    private void markToolLogFailed(ToolCallingRequestContext context, Long logId, String errorMessage, long durationMs) {
        ToolCallLogRecorder recorder = context == null ? null : context.getToolCallLogRecorder();
        if (recorder == null || logId == null) {
            return;
        }
        try {
            recorder.markFailed(logId, errorMessage, durationMs);
        } catch (Exception e) {
            log.warn("[ToolCallLog] mark failed failed, id: {}", logId, e);
        }
    }

    private boolean emitArticleSummaryIfNeeded(String toolResult, ToolCallingStreamCallback callback) {
        JsonNode articleSummary = readArticleSummaryNode(toolResult);
        if (articleSummary == null) {
            return false;
        }
        callback.onToolEvent(SUMMARY_RESULT_EVENT_NAME, toolResult);
        callback.onToken(readChatMessageFromToolResult(toolResult));
        callback.onComplete();
        return true;
    }

    private JsonNode readArticleSummaryNode(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(toolResult);
            return ARTICLE_SUMMARY_RESULT_TYPE.equals(node.path("type").asText("")) ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String readChatMessageFromToolResult(String toolResult) {
        try {
            JsonNode node = objectMapper.readTree(toolResult);
            String chatMessage = node.path("chatMessage").asText("");
            if (chatMessage != null && !chatMessage.isBlank()) {
                return chatMessage;
            }
            String error = node.path("error").asText("");
            return error == null || error.isBlank() ? "工具已执行完成。" : error;
        } catch (Exception e) {
            return toolResult == null || toolResult.isBlank() ? "工具已执行完成。" : toolResult;
        }
    }

    private boolean shouldForceRagSearch(String userMessage, ToolCallingRequestContext requestContext) {
        if (isBlank(userMessage)) {
            return false;
        }
        String text = userMessage.toLowerCase(Locale.ROOT);
        boolean explicitRag = containsAny(text, "知识库", "我的笔记", "笔记里", "我的资料", "项目资料", "保存的内容", "从我的文章", "查一下资料");
        if (!explicitRag) {
            return false;
        }
        return !isAttachmentIntent(userMessage, requestContext);
    }

    private boolean shouldForceRunMavenCompile(String userMessage, ToolCallingRequestContext requestContext) {
        if (isBlank(userMessage)) {
            return false; // 空消息不路由。
        }
        String text = userMessage.toLowerCase(Locale.ROOT); // 统一小写判断。
        if (containsAny(text, "怎么编译", "编译命令是什么", "maven compile 是什么", "什么是 maven compile")) {
            return false; // 说明类问题不执行工具。
        }
        boolean compileIntent = containsAny(text,
                "编译验证", "运行 maven 编译", "执行 mvn compile", "后端编译", "验证后端能不能编译",
                "对这个 workspace 做编译验证", "应用 patch 后编译一下", "mvn compile", "run maven compile"); // 明确 P11 意图。
        boolean hasWorkspaceId = WORKSPACE_ID_PATTERN.matcher(userMessage).find(); // 必须提供 workspaceId。
        boolean hasConfirmToken = userMessage.contains("RUN_COMPILE"); // 必须提供确认标记。
        return compileIntent && hasWorkspaceId && hasConfirmToken; // 三项齐备才强制路由。
    }

    private ObjectNode buildForcedRunMavenCompileArguments(String userMessage) {
        ObjectNode arguments = objectMapper.createObjectNode(); // 构造 runMavenCompile 参数。
        String workspaceId = extractFirstGroup(WORKSPACE_ID_PATTERN, userMessage); // 提取 workspaceId。
        if (!isBlank(workspaceId)) {
            arguments.put("workspaceId", workspaceId); // 写入 workspaceId。
        }
        String module = extractFirstGroup(MODULE_PATTERN, userMessage); // 提取 module。
        if (!isBlank(module)) {
            arguments.put("module", module); // 写入 module。
        }
        ArrayNode profiles = extractCommaSeparatedArray(PROFILES_PATTERN, userMessage); // 提取 profiles。
        if (profiles.size() > 0) {
            arguments.set("profiles", profiles); // 写入 profiles。
        }
        ArrayNode extraArgs = objectMapper.createArrayNode(); // 只允许前端强制路由识别白名单额外参数。
        String text = userMessage.toLowerCase(Locale.ROOT); // 小写用于匹配。
        appendExtraArgIfPresent(extraArgs, text, "-q"); // quiet 参数。
        appendExtraArgIfPresent(extraArgs, text, "-u"); // update 参数。
        appendExtraArgIfPresent(extraArgs, text, "-e"); // errors 参数。
        appendExtraArgIfPresent(extraArgs, text, "-x"); // debug 参数。
        if (extraArgs.size() > 0) {
            normalizeExtraArgs(extraArgs); // 将大小写无关标记规范成 Maven 原参数。
            arguments.set("extraArgs", extraArgs); // 写入 extraArgs。
        }
        String timeout = extractFirstGroup(TIMEOUT_SECONDS_PATTERN, userMessage); // 提取 timeoutSeconds。
        if (!isBlank(timeout)) {
            arguments.put("timeoutSeconds", Integer.parseInt(timeout)); // 写入超时秒数。
        }
        arguments.put("skipTests", !containsAny(text, "skiptests=false", "不跳过测试")); // 默认跳过测试。
        arguments.put("requireConfirm", true); // P11 默认需要确认。
        arguments.put("confirmToken", "RUN_COMPILE"); // 明确确认 token。
        return arguments; // 返回参数。
    }

    private ArrayNode extractCommaSeparatedArray(Pattern pattern, String text) {
        ArrayNode array = objectMapper.createArrayNode(); // 创建数组。
        String raw = extractFirstGroup(pattern, text); // 提取匹配值。
        if (isBlank(raw)) {
            return array; // 未提供时返回空数组。
        }
        for (String part : raw.split(",")) {
            if (!isBlank(part)) {
                array.add(part.trim()); // 添加非空 profile。
            }
        }
        return array; // 返回数组。
    }

    private void appendExtraArgIfPresent(ArrayNode extraArgs, String text, String arg) {
        if (text != null && text.contains(arg)) {
            extraArgs.add(arg); // 只添加消息中出现的白名单参数。
        }
    }

    private void normalizeExtraArgs(ArrayNode extraArgs) {
        for (int i = 0; i < extraArgs.size(); i++) {
            String value = extraArgs.get(i).asText(); // 读取当前参数。
            if ("-u".equals(value)) {
                extraArgs.set(i, objectMapper.getNodeFactory().textNode("-U")); // Maven 使用大写 -U。
            } else if ("-x".equals(value)) {
                extraArgs.set(i, objectMapper.getNodeFactory().textNode("-X")); // Maven 使用大写 -X。
            }
        }
    }

    private boolean shouldForceApplyPatch(String userMessage, ToolCallingRequestContext requestContext) {
        if (isBlank(userMessage)) {
            return false; // 空消息不路由。
        }
        String text = userMessage.toLowerCase(Locale.ROOT); // 统一小写判断。
        if (containsAny(text, "看一下 patch", "预览 patch", "这个 patch 改了什么", "review patch", "preview patch")) {
            return false; // 预览/查看类意图不调用 applyPatch。
        }
        boolean applyIntent = containsAny(text, "应用 patch", "应用这个 patch", "执行 patch", "确认应用 patch", "apply patch"); // 明确应用意图。
        boolean hasWorkspaceId = WORKSPACE_ID_PATTERN.matcher(userMessage).find(); // 必须提供 workspaceId。
        boolean hasConfirmToken = userMessage.contains("APPLY_PATCH"); // 必须提供确认标记。
        boolean hasPatchSource = !isBlank(extractPatchContent(userMessage)) || PATCH_FILE_PATH_PATTERN.matcher(userMessage).find(); // 必须有 patch 内容或路径。
        return applyIntent && hasWorkspaceId && hasConfirmToken && hasPatchSource; // 四项齐备才强制路由。
    }

    private ObjectNode buildForcedApplyPatchArguments(String userMessage) {
        ObjectNode arguments = objectMapper.createObjectNode(); // 构造 applyPatch 参数。
        String workspaceId = extractFirstGroup(WORKSPACE_ID_PATTERN, userMessage); // 提取 workspaceId。
        if (!isBlank(workspaceId)) {
            arguments.put("workspaceId", workspaceId); // 写 workspaceId。
        }
        String patchFilePath = extractFirstGroup(PATCH_FILE_PATH_PATTERN, userMessage); // 提取 patchFilePath。
        if (!isBlank(patchFilePath)) {
            arguments.put("patchFilePath", patchFilePath); // 写 patchFilePath。
        }
        String patchContent = extractPatchContent(userMessage); // 提取 patchContent。
        if (!isBlank(patchContent)) {
            arguments.put("patchContent", patchContent); // 写 patchContent，后续日志会脱敏。
        }
        arguments.put("requireConfirm", true); // P10 默认需要确认。
        arguments.put("confirmToken", "APPLY_PATCH"); // 明确确认 token。
        if (containsAny(userMessage.toLowerCase(Locale.ROOT), "dryrun=true", "dry run", "只校验", "仅校验")) {
            arguments.put("dryRun", true); // 用户明确 dryRun 时只校验。
        }
        return arguments; // 返回参数。
    }

    private String extractPatchContent(String userMessage) {
        String fencedDiff = extractFencedBlock(userMessage, "```diff"); // 优先读取 diff 代码块。
        if (!isBlank(fencedDiff)) {
            return fencedDiff; // 返回 diff 代码块内容。
        }
        String fencedPatch = extractFencedBlock(userMessage, "```patch"); // 兼容 patch 代码块。
        if (!isBlank(fencedPatch)) {
            return fencedPatch; // 返回 patch 代码块内容。
        }
        int diffIndex = userMessage.indexOf("diff --git "); // 查找 git diff 起点。
        if (diffIndex >= 0) {
            return userMessage.substring(diffIndex).trim(); // 从 diff 起点截取。
        }
        int unifiedIndex = userMessage.indexOf("--- a/"); // 兼容无 diff --git 的 unified diff。
        if (unifiedIndex >= 0) {
            return userMessage.substring(unifiedIndex).trim(); // 从 --- a/ 起点截取。
        }
        return null; // 没有 patch 内容。
    }

    private String extractFencedBlock(String text, String fenceStart) {
        int start = text.indexOf(fenceStart); // 找代码块起点。
        if (start < 0) {
            return null; // 没有代码块。
        }
        int contentStart = text.indexOf('\n', start); // 找第一行结尾。
        if (contentStart < 0) {
            return null; // 代码块格式不完整。
        }
        int end = text.indexOf("```", contentStart + 1); // 找结束围栏。
        if (end < 0) {
            return text.substring(contentStart + 1).trim(); // 没结束围栏时取剩余内容。
        }
        return text.substring(contentStart + 1, end).trim(); // 返回围栏内内容。
    }

    private String extractFirstGroup(Pattern pattern, String text) {
        if (pattern == null || text == null) {
            return null; // 参数缺失。
        }
        Matcher matcher = pattern.matcher(text); // 创建 matcher。
        return matcher.find() ? matcher.group(1) : null; // 返回第一组。
    }

    private boolean shouldForceSummarizeArticle(String userMessage, ToolCallingRequestContext requestContext) {
        if (isBlank(userMessage) || hasAttachedFiles(requestContext)) {
            return false;
        }
        String text = userMessage.toLowerCase(Locale.ROOT);
        boolean summarize = containsAny(text, "总结", "摘要", "要点", "梳理", "面试话术");
        boolean articleTarget = containsAny(text, "文章", "笔记", "article", "这篇", "刚才那篇", "上面那篇") || extractLong(ARTICLE_ID_PATTERN, userMessage) != null;
        return summarize && articleTarget;
    }

    private boolean shouldForceReadFile(String userMessage, ToolCallingRequestContext requestContext) {
        if (!hasAttachedFiles(requestContext) && activeFileFocus(requestContext) == null) {
            return false;
        }
        if (isExplicitRagIntent(userMessage)) {
            return false;
        }
        String text = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        if (extractLong(FILE_ID_PATTERN, userMessage) != null) {
            return true;
        }
        return containsAny(text, "读取", "读一下", "打开", "看看", "分析", "总结", "解释", "检查", "这个文件", "附件", "file");
    }

    private ForceReadFileTarget resolveReadFileTarget(String userMessage, ToolCallingRequestContext requestContext) {
        Long explicitFileId = extractLong(FILE_ID_PATTERN, userMessage);
        if (explicitFileId != null) {
            return new ForceReadFileTarget(explicitFileId);
        }
        ChatAttachedFileContext activeFocus = activeFileFocus(requestContext);
        if (activeFocus != null && activeFocus.getFileId() != null) {
            return new ForceReadFileTarget(activeFocus.getFileId());
        }
        List<ChatAttachedFileContext> attachedFiles = requestContext == null ? List.of() : safeList(requestContext.getAttachedFiles());
        if (attachedFiles.size() == 1 && attachedFiles.get(0).getFileId() != null) {
            return new ForceReadFileTarget(attachedFiles.get(0).getFileId());
        }
        return null;
    }

    private String resolveSummaryType(String userMessage) {
        String text = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        if (containsAny(text, "面试", "话术")) {
            return SummaryTypeConstants.INTERVIEW;
        }
        if (containsAny(text, "要点", "重点", "bullet", "列表")) {
            return SummaryTypeConstants.POINTS;
        }
        return SummaryTypeConstants.NORMAL;
    }

    private String buildAttachedFilesPrompt(ToolCallingRequestContext requestContext) {
        List<ChatAttachedFileContext> attachedFiles = requestContext == null ? List.of() : safeList(requestContext.getAttachedFiles());
        List<ChatAttachedFileContext> recentFiles = requestContext == null ? List.of() : safeList(requestContext.getRecentAttachedFiles());
        ChatAttachedFileContext activeFocus = activeFileFocus(requestContext);
        if (attachedFiles.isEmpty() && recentFiles.isEmpty() && activeFocus == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder("\n\n当前会话文件上下文：");
        appendFileList(builder, "\n本轮附件：", attachedFiles);
        if (activeFocus != null) {
            builder.append("\n最近已读取文件：fileId=").append(activeFocus.getFileId())
                    .append(", name=").append(safeInline(activeFocus.getOriginalName()));
        }
        appendFileList(builder, "\n最近附件：", recentFiles);
        builder.append("\n如果用户要求读取、分析或总结文本/代码附件内容，应调用 readFile，并使用对应 fileId。");
        return builder.toString();
    }

    private void appendFileList(StringBuilder builder, String title, List<ChatAttachedFileContext> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        builder.append(title);
        for (ChatAttachedFileContext file : files) {
            if (file == null) {
                continue;
            }
            builder.append("\n- fileId=").append(file.getFileId())
                    .append(", name=").append(safeInline(file.getOriginalName()))
                    .append(", ext=").append(safeInline(file.getFileExt()))
                    .append(", type=").append(safeInline(file.getFileType()));
        }
    }

    private String buildToolResultPrompt(String toolName, JsonNode arguments, String toolResult) {
        return "本轮工具执行结果："
                + "\ntoolName=" + toolName
                + "\narguments_json=" + serializeArgumentsForLog(toolName, arguments)
                + "\nresult_json_or_text=\n" + limitText(toolResult, TOOL_RESULT_MAX_LENGTH);
    }

    private void appendMemoryAndHistory(ArrayNode messages, String memorySummary, List<ToolChatHistoryMessage> historyMessages) {
        if (!isBlank(memorySummary)) {
            addMessage(messages, "system", "会话长期记忆摘要：\n" + limitText(memorySummary, MEMORY_SUMMARY_MAX_LENGTH));
        }
        for (ToolChatHistoryMessage history : normalizeHistory(historyMessages)) {
            addMessage(messages, history.getRole(), limitText(history.getContent(), HISTORY_CONTENT_MAX_LENGTH));
        }
    }

    private void addMessage(ArrayNode messages, String role, String content) {
        if (isBlank(content)) {
            return;
        }
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        messages.add(message);
    }

    private JsonNode parseArguments(String rawArguments) {
        try {
            return objectMapper.readTree(isBlank(rawArguments) ? "{}" : rawArguments);
        } catch (Exception e) {
            log.warn("[ToolChatStream] parse tool arguments failed");
            return objectMapper.createObjectNode();
        }
    }

    private String serialize(JsonNode node) {
        try {
            return node == null ? "{}" : objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String serializeArgumentsForLog(String toolName, JsonNode arguments) {
        if (RUN_MAVEN_COMPILE_TOOL_NAME.equals(toolName)) {
            return serializeRunMavenCompileArgumentsForLog(arguments); // P11 参数日志只保存审计摘要。
        }
        if (!APPLY_PATCH_TOOL_NAME.equals(toolName)) {
            return serialize(arguments); // 其它工具保持原有日志参数。
        }
        try {
            ObjectNode sanitized = objectMapper.createObjectNode(); // applyPatch 参数脱敏副本。
            copyTextForLog(arguments, sanitized, "workspaceId", false); // workspaceId 可记录。
            copyTextForLog(arguments, sanitized, "workspacePath", true); // workspacePath 可能是绝对路径，需要脱敏。
            copyTextForLog(arguments, sanitized, "patchFilePath", true); // patchFilePath 可能是绝对路径，需要脱敏。
            if (arguments != null && arguments.path("allowedDirectories").isArray()) {
                sanitized.set("allowedDirectories", arguments.path("allowedDirectories")); // 白名单目录可记录。
            }
            copyScalarForLog(arguments, sanitized, "dryRun"); // dryRun 可记录。
            copyScalarForLog(arguments, sanitized, "backupEnabled"); // backupEnabled 可记录。
            copyScalarForLog(arguments, sanitized, "rollbackOnFailure"); // rollbackOnFailure 可记录。
            copyScalarForLog(arguments, sanitized, "maxChangedFiles"); // maxChangedFiles 可记录。
            copyScalarForLog(arguments, sanitized, "requireConfirm"); // requireConfirm 可记录。
            sanitized.put("confirmTokenPresent", arguments != null && !arguments.path("confirmToken").asText("").isBlank()); // 只记录是否提供确认。
            String patchContent = arguments == null ? null : arguments.path("patchContent").asText(null); // 读取 patchContent。
            if (patchContent != null && !patchContent.isBlank()) {
                sanitized.put("patchContentRedacted", true); // 明确 patch 全文已脱敏。
                sanitized.put("patchSize", patchContent.getBytes(StandardCharsets.UTF_8).length); // 记录大小。
                sanitized.put("patchHash", sha256(patchContent)); // 记录 hash 便于审计。
            } else {
                sanitized.put("patchContentRedacted", false); // 未传 patchContent。
            }
            return objectMapper.writeValueAsString(sanitized); // 返回脱敏参数。
        } catch (Exception e) {
            return "{\"tool\":\"applyPatch\",\"argumentsRedacted\":true,\"error\":\"failed to sanitize arguments\"}"; // 脱敏失败兜底。
        }
    }

    private String serializeRunMavenCompileArgumentsForLog(JsonNode arguments) {
        try {
            ObjectNode sanitized = objectMapper.createObjectNode(); // runMavenCompile 参数脱敏副本。
            copyTextForLog(arguments, sanitized, "workspaceId", false); // workspaceId 可记录。
            copyTextForLog(arguments, sanitized, "workspacePath", true); // workspacePath 可能是绝对路径，需脱敏。
            copyTextForLog(arguments, sanitized, "module", false); // module 可记录。
            if (arguments != null && arguments.path("profiles").isArray()) {
                sanitized.set("profiles", arguments.path("profiles")); // profiles 可记录。
            }
            if (arguments != null && arguments.path("extraArgs").isArray()) {
                sanitized.set("extraArgs", arguments.path("extraArgs")); // 白名单 Maven 参数可记录。
            }
            copyScalarForLog(arguments, sanitized, "skipTests"); // skipTests 可记录。
            copyScalarForLog(arguments, sanitized, "timeoutSeconds"); // timeoutSeconds 可记录。
            copyScalarForLog(arguments, sanitized, "requireConfirm"); // requireConfirm 可记录。
            sanitized.put("confirmTokenPresent", arguments != null && !arguments.path("confirmToken").asText("").isBlank()); // 只记录是否提供确认标记。
            return objectMapper.writeValueAsString(sanitized); // 返回脱敏参数。
        } catch (Exception e) {
            return "{\"tool\":\"runMavenCompile\",\"argumentsRedacted\":true,\"error\":\"failed to sanitize arguments\"}"; // 脱敏失败兜底。
        }
    }

    private void copyTextForLog(JsonNode source, ObjectNode target, String fieldName, boolean redactAbsolutePath) {
        if (source == null || source.path(fieldName).isMissingNode() || source.path(fieldName).isNull()) {
            return; // 字段缺失时不写。
        }
        String value = source.path(fieldName).asText(""); // 读取文本。
        target.put(fieldName, redactAbsolutePath ? redactAbsolutePath(value) : value); // 按需脱敏绝对路径。
    }

    private void copyScalarForLog(JsonNode source, ObjectNode target, String fieldName) {
        if (source == null || source.path(fieldName).isMissingNode() || source.path(fieldName).isNull()) {
            return; // 字段缺失时不写。
        }
        target.set(fieldName, source.path(fieldName)); // 标量参数直接复制。
    }

    private String redactAbsolutePath(String value) {
        if (value == null) {
            return null; // 空值直接返回。
        }
        String normalized = value.replace('\\', '/'); // 统一分隔符。
        if (normalized.matches("^[A-Za-z]:/.*") || normalized.startsWith("/")) {
            return "[absolute-path-redacted]"; // 绝对路径不入库。
        }
        return value; // 相对路径可记录。
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); // 创建 SHA-256。
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8)); // 计算 hash。
            return HexFormat.of().formatHex(hash); // 转十六进制。
        } catch (Exception e) {
            return "hash_error"; // hash 失败兜底。
        }
    }

    private boolean isAllowedToolName(String toolName) {
        return !isBlank(toolName) && toolRegistry.getTool(toolName) != null;
    }

    private String toolTypeOf(String toolName) {
        if (RAG_SEARCH_TOOL_NAME.equals(toolName)) {
            return TOOL_TYPE_RAG;
        }
        if (SUMMARIZE_ARTICLE_TOOL_NAME.equals(toolName)) {
            return TOOL_TYPE_SUMMARY;
        }
        if (READ_FILE_TOOL_NAME.equals(toolName)) {
            return TOOL_TYPE_FILE;
        }
        if (APPLY_PATCH_TOOL_NAME.equals(toolName)) {
            return TOOL_TYPE_SELF_DEV; // P10 applyPatch 属于自迭代高风险写操作，日志中单独标记。
        }
        if (RUN_MAVEN_COMPILE_TOOL_NAME.equals(toolName)) {
            return TOOL_TYPE_SELF_DEV; // P11 runMavenCompile 属于自迭代命令执行能力。
        }
        return TOOL_TYPE_OTHER;
    }

    private List<ToolChatHistoryMessage> normalizeHistory(List<ToolChatHistoryMessage> historyMessages) {
        if (historyMessages == null || historyMessages.isEmpty()) {
            return Collections.emptyList();
        }
        List<ToolChatHistoryMessage> normalized = new ArrayList<>();
        int start = Math.max(0, historyMessages.size() - HISTORY_MESSAGE_LIMIT);
        for (int i = start; i < historyMessages.size(); i++) {
            ToolChatHistoryMessage message = historyMessages.get(i);
            if (message == null || isBlank(message.getContent())) {
                continue;
            }
            String role = ROLE_ASSISTANT.equals(message.getRole()) ? ROLE_ASSISTANT : ROLE_USER;
            ToolChatHistoryMessage copy = new ToolChatHistoryMessage();
            copy.setRole(role);
            copy.setContent(limitText(message.getContent(), HISTORY_CONTENT_MAX_LENGTH));
            normalized.add(copy);
        }
        return normalized;
    }

    private Long extractLong(Pattern pattern, String text) {
        if (pattern == null || text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasAttachedFiles(ToolCallingRequestContext context) {
        return context != null && context.getAttachedFiles() != null && !context.getAttachedFiles().isEmpty();
    }

    private ChatAttachedFileContext activeFileFocus(ToolCallingRequestContext context) {
        return context == null ? null : context.getActiveFileFocus();
    }

    private boolean isAttachmentIntent(String userMessage, ToolCallingRequestContext context) {
        if (!hasAttachedFiles(context) && activeFileFocus(context) == null) {
            return false;
        }
        String text = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        return containsAny(text, "附件", "文件", "file", "上传", "这份", "这个");
    }

    private boolean isExplicitRagIntent(String userMessage) {
        String text = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        return containsAny(text, "知识库", "我的笔记", "我的资料", "保存的内容");
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isEmpty() && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<ChatAttachedFileContext> safeList(List<ChatAttachedFileContext> files) {
        return files == null ? Collections.emptyList() : files;
    }

    private String safeInline(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String limitText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (maxLength <= 0 || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "\n...（内容已截断）";
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private record ForceReadFileTarget(Long fileId) {
    }
}

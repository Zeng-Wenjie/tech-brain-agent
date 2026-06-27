package com.agent.service.impl;

import com.agent.entity.ChatMessage;
import com.agent.entity.ChatMessageFile;
import com.agent.entity.Conversation;
import com.agent.entity.ConversationMemory;
import com.agent.entity.UserFile;
import com.agent.entity.dto.ChatRequestDTO;
import com.agent.entity.dto.ToolCallLogCreateRequest;
import com.agent.mapper.ChatMessageMapper;
import com.agent.mapper.ConversationMapper;
import com.agent.service.ChatMessageFileService;
import com.agent.service.ChatMessageService;
import com.agent.service.ConversationMemoryService;
import com.agent.service.ToolCallLogService;
import com.agent.service.UserFileService;
import com.agent.toolcalling.context.ChatAttachedFileContext;
import com.agent.toolcalling.context.ConversationFocusContext;
import com.agent.toolcalling.context.ConversationFocusService;
import com.agent.toolcalling.core.ToolChatHistoryMessage;
import com.agent.toolcalling.core.ToolCallingChatService;
import com.agent.toolcalling.core.ToolCallingStreamCallback;
import com.agent.toolcalling.context.ToolCallingRequestContext;
import com.agent.toolcalling.log.ToolCallLogRecorder;
import com.agent.utils.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 前端正式聊天入口 {@code POST /chat/message} 的业务服务实现。
 *
 * <p>适用范围：处理用户在正式聊天页发送的一轮消息，包括会话归属校验、用户消息落库、Tool Calling
 * 流式回调转发、assistant 完整消息保存和 conversation 更新时间刷新。</p>
 * <p>当前主调用链：ChatMessageController -> ChatMessageServiceImpl
 * -> ToolCallingChatService.chatStream(rawUserMessage, memorySummary, toolHistoryMessages, callback) -> ToolRegistry/RagSearchTool
 * -> Milvus -> DeepSeek stream -> SSE token 返回前端。</p>
 * <p>多轮上下文当前处于 4.6.3 阶段：本类读取 conversation_memory.summary 作为长期记忆摘要，同时读取最近短期历史并转换为ToolChatHistoryMessage，
 * 两者都只作为最终回答上下文传给ToolCallingChatService，不拼接multiTurnQuestion，不参与force ragSearch和RAG query。</p>
 * <p>长期记忆写入仍在 assistant 完整回复保存、conversation 更新时间刷新、SSE done 完成后执行，不阻塞前端完成事件。</p>
 */
@Slf4j
@Service
public class ChatMessageServiceImpl implements ChatMessageService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String SUMMARY_RESULT_EVENT_NAME = "summary_result"; // summarizeArticle工具完整总结结果使用该SSE事件名发送。
    private static final int TITLE_MAX_LENGTH = 20;
    private static final int HISTORY_CONTEXT_LIMIT = 100; // 4.3 阶段读取并转换最近 6 条结构化历史，供Tool Calling最终回答阶段使用。
    private static final int RECENT_ATTACHED_FILE_LIMIT = 10; // 会话文件焦点最多加载最近10个附件元信息。
    private static final long SSE_TIMEOUT = 120000L; // SSE 连接最长等待时间，给 DeepSeek 流式生成保留足够窗口。
    private static final String FILE_ONLY_MODEL_MESSAGE = "用户本轮上传了文件但没有输入文字。请根据附件元信息回应；如果需要读取文件内容，请调用 readFile 工具，未读取前不能编造文件内容。"; // 只发文件时给模型的内部隐藏上下文，不写入 chat_message。

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private ToolCallingChatService toolCallingChatService; // /chat/message 唯一正式编排器，负责 Tool Calling 与 DeepSeek 流式输出。

    @Autowired
    private ToolCallLogService toolCallLogService; // 工具调用日志服务，只用于tool_call_log记录和final_answer回填。

    @Autowired
    private ConversationMemoryService conversationMemoryService; // assistant 回复完成后异步线程内更新 conversation_memory 长期记忆。

    @Autowired
    private ChatMessageFileService chatMessageFileService; // 保存和加载 chat_message_file 附件关联。

    @Autowired
    private UserFileService userFileService; // 校验 /chat/message 本轮 fileIds 是否属于当前用户。

    @Autowired
    private ConversationFocusService conversationFocusService; // 读取 readFile 成功后的当前会话文件焦点。

    @Override
    public SseEmitter sendMessage(ChatRequestDTO dto) {
        return sendMessage(dto, false); // /chat/message：默认智能体模式，保持原有 Tool Calling 工具路由行为。
    }

    @Override
    public SseEmitter sendMessage(ChatRequestDTO dto, boolean plainChat) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT); // HTTP 响应先返回 emitter，后续 token 通过同一连接持续推送。
        Long userId = UserContext.getUserId();

        CompletableFuture.runAsync(() -> { // 流式输出不放进 Controller 线程，避免连接等待阻塞请求线程。
            try {
                UserContext.setUserId(userId); // 异步线程手动恢复当前用户，保证 ragSearch/Milvus 检索按用户过滤。
                doSendMessage(dto, userId, emitter, plainChat); // plainChat=true 时为普通聊天，全程不进入任何工具路由。
            } catch (Exception e) {
                sendError(emitter, e);
            } finally {
                UserContext.removeUserId();
            }
        });

        return emitter;
    }

    private void doSendMessage(ChatRequestDTO dto, Long userId, SseEmitter emitter, boolean plainChat) {
        validateRequest(dto); // POST /chat/message 的入口校验，允许“文字”或“附件”至少有一个。
        String rawUserMessage = resolveUserMessage(dto); // 当前轮用户原始输入，兼容 msg/message 入参。
        List<UserFile> attachedUserFiles = resolveAttachedUserFiles(dto, userId); // 校验本轮 fileIds 归属，后续用于关联表入库。
        List<ChatAttachedFileContext> attachedFiles = toAttachedFileContexts(attachedUserFiles); // 转换为 Tool Calling 安全附件元信息。
        String persistedUserMessage = resolvePersistedUserMessage(rawUserMessage); // 只保存用户真实输入，只发文件时保存空字符串。
        String modelCurrentMessage = resolveModelCurrentMessage(rawUserMessage, attachedFiles); // 模型内部 currentMessage，必要时使用隐藏附件上下文。
        log.info("[ChatMessage] route: {}", plainChat ? "/chat/plain (plain chat, tools disabled)" : "/chat/message (agent, tools enabled)"); // 区分普通聊天与智能体模式，便于排查是否误入工具链路。
        log.debug("[ChatMessage] current answer mode: tool-calling-stream"); // answer mode固定，降级为DEBUG。
        log.info("[ChatMessage] user id: {}", userId); // 打印当前用户，确认后续历史和 Milvus 检索按用户隔离。
        log.debug("[ChatMessage] raw user message: {}", previewContent(rawUserMessage)); // 用户原文只在DEBUG打印短preview。
        log.debug("[ChatMessage] attached file count: {}", attachedFiles.size()); // 附件数量只在DEBUG打印，不打印路径或内容。

        LocalDateTime now = LocalDateTime.now();
        Conversation conversation = resolveConversation(dto, userId, now, persistedUserMessage); // 无会话则新建，有会话则校验归属后复用。
        log.debug("[ChatMessage] conversation id: {}", conversation.getId()); // 会话ID细节降级为DEBUG。

        List<ChatMessage> historyMessages = loadRecentHistoryMessages(conversation.getId(), userId); // 保存本轮消息前读取历史，避免包含当前user输入。
        logHistoryMessages(historyMessages); // 打印历史数量和 preview，便于验证结构化历史读取能力。
        List<ToolChatHistoryMessage> toolHistoryMessages = convertToToolHistoryMessages(historyMessages, userId); // 转换为Tool Calling专用历史模型，并注入历史附件元信息。
        logToolHistoryMessages(toolHistoryMessages); // 打印Tool历史数量和preview，便于验证4.3历史传入能力。
        String memorySummary = loadMemorySummary(conversation.getId(), userId); // 读取会话长期记忆摘要，失败时返回空字符串，不影响聊天主流程。

        Long userMessageId = saveMessage(conversation.getId(), userId, ROLE_USER, persistedUserMessage, now); // 用户消息只保存真实输入，拿到 messageId 后才能保存附件关联。
        chatMessageFileService.saveMessageFiles(userMessageId, conversation.getId(), userId, attachedUserFiles); // 写入 chat_message_file，仅保存附件元信息。
        List<ChatAttachedFileContext> recentAttachedFiles = loadRecentAttachedFiles(conversation.getId(), userId); // 加载当前会话最近附件，供“这个文件/继续分析”指代解析。
        ChatAttachedFileContext activeFileFocus = loadActiveFileFocus(conversation.getId(), userId); // 加载最近成功readFile的上传文件焦点，优先处理上传附件指代。

        log.debug("[ChatMessage] use ToolCallingChatService.chatStream: true"); // 调用细节降级为DEBUG。
        log.debug("[ChatMessage] tool-calling current message: {}", previewContent(modelCurrentMessage)); // 模型内部消息只在DEBUG打印短preview。
        String traceId = createTraceId(); // 每轮 /chat/message 生成一个traceId，所有工具调用共享。
        StringBuilder fullAnswer = new StringBuilder(); // 流式 token 先在内存聚合，完成后一次性保存 assistant 消息。
        ToolCallingRequestContext requestContext = new ToolCallingRequestContext(); // 构造工具执行期上下文，只供AiTool读取userId/conversationId。
        requestContext.setTraceId(traceId); // 将本轮traceId传入Tool Calling编排链路。
        requestContext.setToolsEnabled(!plainChat); // 普通聊天(/chat/plain)关闭工具路由，智能体(/chat/message)保持开启，避免聊天时误触工具调用。
        requestContext.setUserId(userId); // 当前登录用户ID来自后端UserContext，不从前端或模型参数读取。
        requestContext.setConversationId(conversation.getId()); // 当前会话ID用于RAG命中后保存conversation级focus。
        requestContext.setCurrentMessage(modelCurrentMessage); // 当前模型内部消息；只发文件时使用隐藏上下文，不保存到 chat_message。
        requestContext.setAttachedFiles(attachedFiles); // 本轮附件只传安全元信息，不包含 storagePath 或文件内容。
        requestContext.setRecentAttachedFiles(recentAttachedFiles); // 最近附件只传安全元信息，用于会话文件焦点记忆。
        requestContext.setActiveFileFocus(activeFileFocus); // 最近成功读取文件焦点只传元信息，用于多附件场景的模糊指代解析。
        requestContext.setToolCallLogRecorder(buildToolCallLogRecorder()); // 注入日志回调，避免Tech-Brain-Tool模块直接依赖Agent服务。
        toolCallingChatService.chatStream(modelCurrentMessage, memorySummary, toolHistoryMessages, requestContext, new ToolCallingStreamCallback() { // 传入长期记忆、结构化历史和工具上下文，禁止恢复multiTurnQuestion。
            @Override
            public void onToken(String token) { // 收到最终回答的增量 token。
                try {
                    fullAnswer.append(token); // 累积完整 assistant 回复，完成后写入 chat_message。
                    Map<String, String> data = new HashMap<>(); // 沿用当前 SSE message 事件数据结构，前端无需改造。
                    data.put("content", token); // 每次只发送增量 token，保持前端逐 token 流式显示。
                    emitter.send(SseEmitter.event().name("message").data(data)); // 通过 SSE message 事件推送 token。
                } catch (IOException e) {
                    throw new RuntimeException("Tool Calling 流式 token 发送失败", e); // 交给上层 onError/异常兜底处理。
                }
            }

            @Override
            public void onComplete() { // DeepSeek 流式输出结束。
                try {
                    log.info("[ChatMessage] stream complete, save assistant message"); // 标记流式完成后开始保存完整 assistant 回复。
                    saveMessage(conversation.getId(), userId, ROLE_ASSISTANT, fullAnswer.toString(), LocalDateTime.now()); // 保存完整 assistant 消息，保证聊天历史完整。
                    log.info("[ChatMessage] assistant message saved length: {}", fullAnswer.length()); // 只打印保存长度，不打印 assistant 完整内容。
                    updateToolCallFinalAnswer(traceId, fullAnswer.toString()); // assistant消息保存后按traceId回填工具日志final_answer。
                    updateConversationTime(conversation.getId(), userId); // 刷新会话更新时间，保持会话列表排序正确。
                    Map<String, Object> doneData = new HashMap<>(); // done 事件携带 conversationId，保持前端完成事件格式不变。
                    doneData.put("conversationId", conversation.getId()); // 返回真实会话 ID，兼容新会话首轮发送场景。
                    log.debug("[ChatMessage] send done event after stream"); // done事件发送细节降级为DEBUG。
                    emitter.send(SseEmitter.event().name("done").data(doneData)); // 通知前端本轮流式回答完成。
                    emitter.complete(); // 正常结束 SSE 连接。
                } catch (Exception e) {
                    sendError(emitter, e); // 保存消息或发送 done 失败时走统一错误事件。
                    return; // 主流程失败时不更新长期记忆，避免记录不完整回答。
                }
                updateConversationMemoryAfterAnswer(conversation.getId(), userId,
                        buildMemoryUserMessage(persistedUserMessage, attachedFiles), fullAnswer.toString()); // SSE完成后再更新长期记忆，附件只写简短元信息。
            }

            @Override
            public void onToolEvent(String eventName, String payloadJson) { // Tool Calling工具产生业务事件时触发，当前用于转发文章总结完整结果。
                if (!SUMMARY_RESULT_EVENT_NAME.equals(eventName)) { // 只处理summary_result，其它未来事件保持忽略以避免影响聊天主流程。
                    return; // 非当前业务事件不写入SSE、不写入fullAnswer。
                }
                try {
                    log.info("[ChatMessage] send SSE tool event: {}", SUMMARY_RESULT_EVENT_NAME); // 只打印事件名，不打印完整summary。
                    emitter.send(SseEmitter.event().name(SUMMARY_RESULT_EVENT_NAME).data(payloadJson)); // 发送自定义SSE事件给前端弹窗监听。
                } catch (IOException e) {
                    sendError(emitter, e); // 按现有SSE异常处理风格发送error并结束连接。
                }
            }

            @Override
            public void onError(Throwable error) { // Tool Calling 编排、工具执行或 DeepSeek 流式调用异常。
                sendError(emitter, error); // 统一发送 SSE error 事件并结束连接。
            }
        });
    }

    private void validateRequest(ChatRequestDTO dto) {
        if (dto == null) { // 请求体不能为空。
            throw new IllegalArgumentException("请输入内容或添加文件"); // 统一提示文字和附件至少需要一个。
        }
        if (!hasText(resolveUserMessage(dto)) && !hasFileIds(dto)) { // 用户没有输入文字，也没有选择文件。
            throw new IllegalArgumentException("请输入内容或添加文件"); // 允许只发文件，但不允许空请求。
        }
    }

    private String resolveUserMessage(ChatRequestDTO dto) {
        return dto == null ? null : dto.getMsg(); // ChatRequestDTO 通过 @JsonAlias 兼容 message 入参，内部统一读取 msg。
    }

    private boolean hasFileIds(ChatRequestDTO dto) {
        return dto != null && dto.getFileIds() != null && !dto.getFileIds().isEmpty(); // fileIds 非空即可视为本轮有附件输入。
    }

    private String resolvePersistedUserMessage(String rawUserMessage) {
        return hasText(rawUserMessage) ? rawUserMessage : ""; // 只发文件时 chat_message.content 保存空字符串，不保存隐藏提示。
    }

    private String resolveModelCurrentMessage(String rawUserMessage, List<ChatAttachedFileContext> attachedFiles) {
        if (hasText(rawUserMessage)) { // 用户有真实文本输入时，模型 currentMessage 使用原文。
            return rawUserMessage; // 保持普通文字聊天行为不变。
        }
        if (attachedFiles != null && !attachedFiles.isEmpty()) { // 用户只发送附件时，给模型内部隐藏上下文。
            return FILE_ONLY_MODEL_MESSAGE; // 不写入 chat_message，也不返回前端。
        }
        return ""; // 理论上入口校验已拦截空文本空附件，这里兜底。
    }

    private List<UserFile> resolveAttachedUserFiles(ChatRequestDTO dto, Long userId) {
        List<Long> fileIds = dto == null ? null : dto.getFileIds(); // 读取本轮前端传入的文件 ID 列表。
        if (fileIds == null || fileIds.isEmpty()) { // fileIds 可为空，不影响原普通聊天。
            return Collections.emptyList(); // 返回空附件上下文。
        }
        if (userId == null) { // 附件校验必须绑定当前登录用户。
            throw new IllegalArgumentException("未登录或登录状态失效"); // 不允许匿名用户携带 fileId。
        }

        Set<Long> distinctFileIds = new LinkedHashSet<>(); // 保持前端选择顺序，同时避免重复文件重复注入上下文。
        for (Long fileId : fileIds) { // 遍历每个 fileId 做基础合法性检查。
            if (fileId == null) { // 空 ID 无法校验归属。
                throw new IllegalArgumentException("文件不存在或无权访问"); // 使用统一提示避免暴露细节。
            }
            distinctFileIds.add(fileId); // 记录非空文件 ID。
        }

        List<UserFile> attachedUserFiles = new ArrayList<>(); // 保存已校验归属的 UserFile，后续写入 chat_message_file。
        for (Long fileId : distinctFileIds) { // 对去重后的文件 ID 逐个校验权限。
            UserFile userFile = userFileService.getByIdAndUserId(fileId, userId); // 强制 id + user_id + status=1 查询。
            if (userFile == null) { // 文件不存在、已失效或不属于当前用户。
                throw new IllegalArgumentException("文件不存在或无权访问"); // 拒绝跨用户 fileId。
            }
            attachedUserFiles.add(userFile); // 保留已校验文件实体，仅在后端内部用于元信息入库和上下文转换。
        }
        log.info("[ChatMessage] attached files verified, userId: {}, count: {}", userId, attachedUserFiles.size()); // 只打印数量，不打印路径。
        return attachedUserFiles; // 返回已校验归属的文件列表。
    }

    private List<ChatAttachedFileContext> toAttachedFileContexts(List<UserFile> userFiles) {
        if (userFiles == null || userFiles.isEmpty()) { // 没有附件文件时返回空上下文。
            return Collections.emptyList(); // 保持普通聊天不带 attachedFiles。
        }
        List<ChatAttachedFileContext> attachedFiles = new ArrayList<>(); // 构造 Tool Calling 安全附件元信息列表。
        for (UserFile userFile : userFiles) { // 遍历已校验文件。
            if (userFile == null) { // 兜底过滤空对象。
                continue; // 跳过空文件。
            }
            attachedFiles.add(toAttachedFileContext(userFile)); // 只转换安全元信息，不包含 storagePath。
        }
        return attachedFiles; // 返回 ToolCallingRequestContext 使用的附件上下文。
    }

    private ChatAttachedFileContext toAttachedFileContext(UserFile userFile) {
        ChatAttachedFileContext context = new ChatAttachedFileContext(); // 创建 Tool Calling 附件上下文对象。
        context.setFileId(userFile.getId()); // 写入文件 ID。
        context.setOriginalName(userFile.getOriginalName()); // 写入原始文件名。
        context.setFileExt(userFile.getFileExt()); // 写入扩展名。
        context.setFileType(userFile.getFileType()); // 写入业务文件类型。
        context.setMimeType(userFile.getMimeType()); // 写入 MIME 类型。
        context.setFileSize(userFile.getFileSize()); // 写入文件大小。
        return context; // 不写入 storedName、storagePath 或文件内容。
    }

    private List<ChatAttachedFileContext> loadRecentAttachedFiles(Long conversationId, Long userId) {
        if (conversationId == null || userId == null) { // 缺少会话或用户时不能加载文件焦点。
            return Collections.emptyList(); // 返回空列表，避免误查附件。
        }
        try {
            List<ChatMessageFile> recentFiles = chatMessageFileService.listByConversationId(
                    userId, conversationId, RECENT_ATTACHED_FILE_LIMIT); // 按当前用户和会话查询最近附件关联。
            if (recentFiles == null || recentFiles.isEmpty()) { // 当前会话没有历史附件。
                return Collections.emptyList(); // 返回空列表。
            }
            Set<Long> seenFileIds = new LinkedHashSet<>(); // 按 create_time desc 查询结果去重，保留最近一次出现的 fileId。
            List<ChatAttachedFileContext> recentAttachedFiles = new ArrayList<>(); // 构造 Tool Calling 最近附件上下文。
            for (ChatMessageFile file : recentFiles) { // 遍历最近附件关联。
                if (file == null || file.getFileId() == null) { // 异常记录跳过。
                    continue; // 不注入无效附件。
                }
                if (!seenFileIds.add(file.getFileId())) { // 同一个文件在会话里多次出现时只保留最近一次。
                    continue; // 跳过重复 fileId。
                }
                recentAttachedFiles.add(toAttachedFileContext(file)); // 只转换安全元信息，不包含 storagePath。
            }
            log.debug("[ChatMessage] recent attached file count: {}", recentAttachedFiles.size()); // 只打印数量，不打印文件内容。
            return recentAttachedFiles; // 返回最近附件焦点列表。
        } catch (Exception e) {
            log.warn("[ChatMessage] load recent attached files failed, conversationId: {}, userId: {}",
                    conversationId, userId, e); // 历史附件加载失败不能影响聊天主流程。
            return Collections.emptyList(); // 兜底为空，保持普通聊天可用。
        }
    }

    private ChatAttachedFileContext loadActiveFileFocus(Long conversationId, Long userId) {
        if (conversationId == null || userId == null) { // 缺少会话或用户时不能读取activeFileFocus。
            return null; // 返回空焦点。
        }
        try {
            ConversationFocusContext focus = conversationFocusService.getActiveFileFocus(userId, conversationId); // 按当前用户和会话读取最近成功readFile焦点。
            if (focus == null || focus.getSourceId() == null) { // 没有文件焦点。
                return null; // 返回空焦点。
            }
            ChatAttachedFileContext context = new ChatAttachedFileContext(); // 转换为Tool Calling附件上下文。
            context.setFileId(focus.getSourceId()); // sourceId 对应 user_file.id。
            context.setOriginalName(focus.getTitle()); // title 保存原始文件名。
            context.setFileExt(focus.getFileExt()); // 写入扩展名。
            context.setFileType(focus.getFileType()); // 写入业务文件类型。
            context.setMimeType(focus.getMimeType()); // 写入 MIME 类型。
            context.setFileSize(focus.getFileSize()); // 写入文件大小。
            log.debug("[ChatMessage] active file focus loaded, fileId: {}", context.getFileId()); // 只打印ID，不打印路径或内容。
            return context; // 返回activeFileFocus元信息。
        } catch (Exception e) {
            log.warn("[ChatMessage] load active file focus failed, conversationId: {}, userId: {}",
                    conversationId, userId, e); // 读取失败不能影响聊天主流程。
            return null; // 兜底为空。
        }
    }

    private ChatAttachedFileContext toAttachedFileContext(ChatMessageFile file) {
        ChatAttachedFileContext context = new ChatAttachedFileContext(); // 创建 Tool Calling 附件上下文对象。
        context.setFileId(file.getFileId()); // 写入 user_file.id。
        context.setOriginalName(file.getOriginalName()); // 写入附件原始文件名快照。
        context.setFileExt(file.getFileExt()); // 写入扩展名快照。
        context.setFileType(file.getFileType()); // 写入业务文件类型快照。
        context.setMimeType(file.getMimeType()); // 写入 MIME 类型快照。
        context.setFileSize(file.getFileSize()); // 写入文件大小快照。
        return context; // 不写入 storedName、storagePath 或文件内容。
    }

    private Conversation resolveConversation(ChatRequestDTO dto, Long userId, LocalDateTime now, String rawUserMessage) {
        if (dto.getConversationId() == null) {
            Conversation conversation = new Conversation(); // 未传会话 ID 时自动创建新会话。
            conversation.setUserId(userId);
            conversation.setTitle(buildTitle(rawUserMessage));
            conversation.setCreateTime(now);
            conversation.setUpdateTime(now);
            conversationMapper.insert(conversation);
            return conversation;
        }

        Conversation conversation = conversationMapper.selectById(dto.getConversationId()); // 已有会话必须查库确认真实存在。
        if (conversation == null || !userId.equals(conversation.getUserId())) { // 校验 conversation.userId，防止用户伪造会话 ID 继续对话。
            throw new IllegalArgumentException("无权访问该会话");
        }
        return conversation;
    }

    private Long saveMessage(Long conversationId, Long userId, String role, String content, LocalDateTime createTime) {
        ChatMessage message = new ChatMessage(); // 用户消息先保存，assistant 消息在流式完成后保存，保证历史可追溯。
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        message.setCreateTime(createTime);
        chatMessageMapper.insert(message);
        return message.getId(); // MyBatis-Plus 使用数据库自增 ID 回填 messageId。
    }

    private List<ChatMessage> loadRecentHistoryMessages(Long conversationId, Long userId) {
        try {
            List<ChatMessage> recentMessages = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>() // 先按倒序查最近历史，避免读取整段会话。
                    .eq(ChatMessage::getConversationId, conversationId)
                    .eq(ChatMessage::getUserId, userId)
                    .in(ChatMessage::getRole, ROLE_USER, ROLE_ASSISTANT)
                    .orderByDesc(ChatMessage::getCreateTime)
                    .orderByDesc(ChatMessage::getId)
                    .last("LIMIT " + HISTORY_CONTEXT_LIMIT));
            List<ChatMessage> historyMessages = new ArrayList<>(recentMessages); // 拷贝一份列表，后续过滤和反转不影响 MyBatis 返回对象。
            historyMessages.removeIf(message -> message == null
                    || !isHistoryRole(message.getRole())); // 只保留 user/assistant，空用户消息可能仍有附件上下文。
            Collections.reverse(historyMessages); // 倒序查询后反转为时间正序，后续可直接按顺序传模型。
            return historyMessages;
        } catch (Exception e) {
            log.warn("[ChatContext] load recent history messages failed, conversationId: {}, userId: {}", conversationId, userId, e);
            return Collections.emptyList(); // 历史读取失败不能影响当前 /chat/message 主流程。
        }
    }

    private boolean isHistoryRole(String role) {
        return ROLE_USER.equals(role) || ROLE_ASSISTANT.equals(role); // 历史上下文只允许普通用户消息和 assistant 回复。
    }

    private List<ToolChatHistoryMessage> convertToToolHistoryMessages(List<ChatMessage> historyMessages, Long userId) {
        if (historyMessages == null || historyMessages.isEmpty()) {
            return Collections.emptyList(); // 没有历史时直接返回空列表，不影响当前主流程。
        }

        Map<Long, List<ChatMessageFile>> messageFileMap = loadHistoryMessageFiles(historyMessages, userId); // 查询历史用户消息对应的附件元信息。
        List<ToolChatHistoryMessage> toolHistoryMessages = new ArrayList<>(); // 创建新列表，避免修改原始ChatMessage历史集合。
        for (ChatMessage historyMessage : historyMessages) {
            if (historyMessage == null || !isHistoryRole(historyMessage.getRole())) {
                continue; // 转换阶段再次兜底过滤非法role。
            }
            List<ChatMessageFile> files = messageFileMap.get(historyMessage.getId()); // 当前历史消息关联的附件元信息。
            boolean contentHasText = hasText(historyMessage.getContent()); // 判断数据库原始 content 是否有真实文本。
            boolean hasFiles = files != null && !files.isEmpty(); // 判断当前消息是否有关联附件。
            if (ROLE_ASSISTANT.equals(historyMessage.getRole()) && !contentHasText) { // assistant 空消息没有上下文价值。
                continue; // 跳过空 assistant 消息。
            }
            if (ROLE_USER.equals(historyMessage.getRole()) && !contentHasText && !hasFiles) { // 用户空消息且无附件。
                continue; // 跳过无意义历史。
            }
            String content = contentHasText ? historyMessage.getContent() : ""; // 默认使用数据库原始 content，空附件消息不伪造前端展示文本。
            if (ROLE_USER.equals(historyMessage.getRole())) { // 只有用户消息才拼接该条消息的附件元信息。
                content = appendHistoryFileContext(content, files); // 只拼到模型上下文，不修改 chat_message。
            }
            toolHistoryMessages.add(ToolChatHistoryMessage.builder()
                    .role(historyMessage.getRole())
                    .content(content)
                    .createTime(historyMessage.getCreateTime())
                    .build()); // 按原顺序转换为Tool Calling公共历史消息模型。
        }
        return toolHistoryMessages;
    }

    private Map<Long, List<ChatMessageFile>> loadHistoryMessageFiles(List<ChatMessage> historyMessages, Long userId) {
        if (historyMessages == null || historyMessages.isEmpty()) { // 没有历史消息时不查询附件关联。
            return Collections.emptyMap(); // 返回空映射。
        }
        List<Long> messageIds = new ArrayList<>(); // 收集用户历史消息 ID。
        for (ChatMessage historyMessage : historyMessages) { // 遍历已加载的历史消息。
            if (historyMessage != null
                    && ROLE_USER.equals(historyMessage.getRole())
                    && historyMessage.getId() != null) { // 只查询用户消息附件。
                messageIds.add(historyMessage.getId()); // 保存消息 ID。
            }
        }
        if (messageIds.isEmpty()) { // 没有用户消息 ID 时不查关联表。
            return Collections.emptyMap(); // 返回空映射。
        }

        List<ChatMessageFile> messageFiles = chatMessageFileService.listByMessageIds(userId, messageIds); // 按 userId + messageIds 查询附件元信息。
        if (messageFiles.isEmpty()) { // 没有历史附件关联。
            return Collections.emptyMap(); // 返回空映射。
        }
        Map<Long, List<ChatMessageFile>> messageFileMap = new HashMap<>(); // 按 messageId 分组附件。
        for (ChatMessageFile messageFile : messageFiles) { // 遍历附件关联。
            if (messageFile == null || messageFile.getMessageId() == null) { // 过滤异常记录。
                continue; // 跳过无效附件关联。
            }
            messageFileMap.computeIfAbsent(messageFile.getMessageId(), key -> new ArrayList<>()).add(messageFile); // 追加到对应消息。
        }
        return messageFileMap; // 返回消息 ID 到附件列表的映射。
    }

    private String appendHistoryFileContext(String content, List<ChatMessageFile> files) {
        if (files == null || files.isEmpty()) { // 当前历史消息没有附件。
            return content; // 保持原始消息内容。
        }
        StringBuilder builder = new StringBuilder(); // 只构造发给模型的上下文，不修改数据库 content。
        if (hasText(content)) { // 历史消息有真实用户文本时保留原文。
            builder.append(content); // 使用数据库原始 content。
            builder.append("\n\n[本条消息附件]\n"); // 附件上下文标题。
        } else { // 历史消息是只发附件的空文本消息。
            builder.append("用户曾发送文件附件：\n"); // 只给模型看的历史附件上下文，不返回前端。
        }
        for (ChatMessageFile file : files) { // 遍历本条消息附件。
            if (file == null) { // 兜底过滤空附件。
                continue; // 跳过。
            }
            builder.append("* fileId=")
                    .append(file.getFileId())
                    .append(", 文件名=")
                    .append(safeText(file.getOriginalName()))
                    .append(", 类型=")
                    .append(safeText(file.getFileExt()))
                    .append(", 大小=")
                    .append(formatFileSize(file.getFileSize()))
                    .append("\n"); // 只拼接安全元信息。
        }
        builder.append("提示：以上只是附件元信息，不包含文件正文；如需读取文件内容，请调用 readFile 工具，未读取前不能编造文件内容。"); // 防止模型冒充已读文件。
        return builder.toString(); // 返回增强后的模型上下文。
    }

    private void logHistoryMessages(List<ChatMessage> historyMessages) {
        log.debug("[ChatContext] load recent history messages"); // 历史读取细节降级为DEBUG。
        log.debug("[ChatContext] history limit: {}", HISTORY_CONTEXT_LIMIT); // 历史窗口大小降级为DEBUG。
        log.debug("[ChatContext] history message count: {}", historyMessages.size()); // 历史数量降级为DEBUG。
        for (ChatMessage message : historyMessages) {
            log.debug("[ChatContext] history item role: {}, content preview: {}", message.getRole(), previewContent(message.getContent())); // 单条preview只在DEBUG打印。
        }
    }

    private void logToolHistoryMessages(List<ToolChatHistoryMessage> toolHistoryMessages) {
        log.debug("[ChatContext] tool history message count: {}", toolHistoryMessages.size()); // Tool历史数量降级为DEBUG。
        for (ToolChatHistoryMessage message : toolHistoryMessages) {
            log.debug("[ChatContext] tool history item role: {}, content preview: {}", message.getRole(), previewContent(message.getContent())); // Tool历史preview只在DEBUG打印。
        }
    }

    private String loadMemorySummary(Long conversationId, Long userId) {
        try { // 长期记忆读取失败不能影响 /chat/message 主链路。
            ConversationMemory memory = conversationMemoryService.getByConversationAndUser(conversationId, userId); // 按会话和用户读取已存在的长期记忆，不主动创建。
            String memorySummary = memory == null ? "" : memory.getSummary(); // 没有 memory 记录时使用空字符串。
            logMemorySummary(memorySummary); // 打印长期记忆读取状态和短preview。
            return memorySummary == null ? "" : memorySummary; // 传给ToolCalling前兜底为非null字符串。
        } catch (Exception e) {
            log.warn("[ChatContext] load memory summary failed, conversationId: {}, userId: {}", conversationId, userId, e); // 读取失败只打印warn。
            logMemorySummary(""); // 失败时明确打印未启用长期记忆。
            return ""; // 使用空长期记忆继续当前聊天。
        }
    }

    private void logMemorySummary(String memorySummary) {
        boolean loaded = memorySummary != null && !memorySummary.trim().isEmpty(); // summary 非空才视为成功加载。
        String normalizedSummary = memorySummary == null ? "" : memorySummary.trim(); // 日志长度和preview使用trim后的内容。
        log.debug("[ChatContext] memory summary loaded: {}", loaded); // 长期记忆状态降级为DEBUG。
        log.debug("[ChatContext] memory summary length: {}", normalizedSummary.length()); // 长期记忆长度降级为DEBUG。
        log.debug("[ChatContext] memory summary preview: {}", previewContent(normalizedSummary)); // memory preview不能在INFO打印。
    }

    private String buildMemoryUserMessage(String rawUserMessage, List<ChatAttachedFileContext> attachedFiles) {
        if (attachedFiles == null || attachedFiles.isEmpty()) { // 本轮没有附件时不改变长期记忆输入。
            return rawUserMessage; // 只记录原始用户消息。
        }
        StringBuilder builder = new StringBuilder(); // 构造长期记忆安全输入，不写隐藏模型提示。
        if (hasText(rawUserMessage)) { // 用户有真实文本时保留原始表达。
            builder.append(rawUserMessage); // 写入用户真实消息。
            builder.append("\n\n[附件："); // 长期记忆只记录简短附件元信息。
        } else { // 用户只发送文件时，不把隐藏提示当作用户真实表达。
            builder.append("用户本轮上传或关联了附件：["); // 只记录文件元信息摘要。
        }
        for (int i = 0; i < attachedFiles.size(); i++) { // 遍历本轮附件。
            ChatAttachedFileContext file = attachedFiles.get(i); // 当前附件元信息。
            if (i > 0) { // 多个附件之间用逗号分隔。
                builder.append(", "); // 分隔符。
            }
            builder.append(safeText(file == null ? null : file.getOriginalName()))
                    .append("(")
                    .append(safeText(file == null ? null : file.getFileExt()))
                    .append(")"); // 只写文件名和扩展名，不写内容。
        }
        builder.append("]"); // 结束附件摘要。
        return builder.toString(); // 返回给 conversation_memory 更新链路的安全文本。
    }

    private String safeText(String value) {
        if (value == null || value.trim().isEmpty()) { // 元信息为空时使用占位。
            return "-"; // 避免上下文里出现 null。
        }
        String normalizedValue = value.replace('\n', ' ').replace('\r', ' ').trim(); // 去掉换行，避免文件名污染上下文结构。
        return normalizedValue.length() <= 120 ? normalizedValue : normalizedValue.substring(0, 120) + "..."; // 限制单个元信息字段长度。
    }

    private String formatFileSize(Long fileSize) {
        if (fileSize == null || fileSize < 0) { // 文件大小缺失或异常时兜底。
            return "未知"; // 返回友好占位。
        }
        if (fileSize < 1024) { // 小于 1KB 时按字节展示。
            return fileSize + "B"; // 返回字节。
        }
        if (fileSize < 1024 * 1024) { // 小于 1MB 时按 KB 展示。
            return (fileSize / 1024) + "KB"; // 返回 KB。
        }
        return (fileSize / (1024 * 1024)) + "MB"; // 大于等于 1MB 时按 MB 展示。
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty(); // 判断字符串是否包含有效内容。
    }

    private String previewContent(String content) {
        if (content == null) {
            return "";
        }
        String normalizedContent = content.replace('\n', ' ').replace('\r', ' '); // 日志预览去掉换行，保持单行可读。
        return normalizedContent.length() <= 80 ? normalizedContent : normalizedContent.substring(0, 80) + "..."; // 只保留前 80 字作为日志预览。
    }

    private String createTraceId() { // 创建本轮聊天请求追踪ID。
        return UUID.randomUUID().toString().replace("-", ""); // 使用无横线UUID，适配trace_id VARCHAR(64)。
    }

    private ToolCallLogRecorder buildToolCallLogRecorder() { // 构造跨模块日志回调适配器。
        return new ToolCallLogRecorder() { // 匿名实现只负责把Tool模块回调转发到Agent日志服务。
            @Override
            public Long createRunningLog(String traceId,
                                         Long conversationId,
                                         Long userId,
                                         String userMessage,
                                         String toolName,
                                         String toolType,
                                         String callSource,
                                         String routeReason,
                                         String argumentsJson) { // 创建运行中日志。
                ToolCallLogCreateRequest request = new ToolCallLogCreateRequest(); // 构造内部Service入参DTO。
                request.setTraceId(traceId); // 写入本轮traceId。
                request.setConversationId(conversationId); // 写入会话ID。
                request.setUserId(userId); // 写入用户ID。
                request.setUserMessage(userMessage); // 写入当前用户原始输入。
                request.setToolName(toolName); // 写入工具名。
                request.setToolType(toolType); // 写入工具类型。
                request.setCallSource(callSource); // 写入调用来源。
                request.setRouteReason(routeReason); // 写入路由原因。
                request.setArgumentsJson(argumentsJson); // 写入工具参数JSON快照。
                return toolCallLogService.createRunningLog(request); // 委托日志服务保存tool_call_log。
            }

            @Override
            public void markSuccess(Long id, String resultJson, Long durationMs) { // 标记工具技术执行成功。
                toolCallLogService.markSuccess(id, resultJson, durationMs); // 委托日志服务写入result_json和duration_ms。
            }

            @Override
            public void markFailed(Long id, String errorMessage, Long durationMs) { // 标记工具执行抛异常。
                toolCallLogService.markFailed(id, errorMessage, durationMs); // 委托日志服务写入error_message和duration_ms。
            }
        };
    }

    private void updateToolCallFinalAnswer(String traceId, String finalAnswer) { // 流式完成后回填同traceId下工具日志的最终聊天气泡内容。
        try {
            log.info("[ChatMessage] update tool call final answer, traceId: {}", traceId); // 只打印traceId，不打印完整finalAnswer。
            toolCallLogService.updateFinalAnswerByTraceId(traceId, finalAnswer); // 没有工具调用记录时日志服务会安全返回。
        } catch (Exception e) {
            log.warn("[ToolCallLog] update final answer failed, traceId: {}, error: {}", traceId, e.getMessage(), e); // 日志回填失败不能影响SSE完成。
        }
    }

    private void updateConversationTime(Long conversationId, Long userId) {
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>() // 回复完成后刷新会话时间，用于会话列表按最近活跃排序。
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId)
                .set(Conversation::getUpdateTime, LocalDateTime.now()));
    }

    private void updateConversationMemoryAfterAnswer(Long conversationId,
                                                     Long userId,
                                                     String rawUserMessage,
                                                     String assistantAnswer) {
        try { // 长期记忆写入失败不能影响已经完成的前端聊天结果。
            log.debug("[ChatMessage] update conversation memory after assistant answer"); // 长期记忆更新细节降级为DEBUG。
            log.debug("[ChatMessage] conversation memory update submitted"); // 长期记忆更新细节降级为DEBUG。
            conversationMemoryService.updateMemoryAfterChat(conversationId, userId, rawUserMessage, assistantAnswer); // 只传当前原始输入和完整助手回复，不传历史拼接字符串。
            log.debug("[ChatMessage] conversation memory update done"); // 长期记忆更新细节降级为DEBUG。
        } catch (Exception e) {
            log.warn("[ChatMessage] conversation memory update failed, conversationId: {}, userId: {}, error: {}",
                    conversationId, userId, e.getMessage(), e); // 兜底捕获，避免长期记忆异常向外扩散。
        }
    }

    private String buildTitle(String msg) {
        String title = msg == null ? "新会话" : msg.trim();
        if (title.isEmpty()) {
            return "新会话";
        }
        return title.length() > TITLE_MAX_LENGTH ? title.substring(0, TITLE_MAX_LENGTH) : title;
    }

    private void sendError(SseEmitter emitter, Throwable error) {
        try { // error 事件尽量发给前端，便于 UI 结束加载态并展示失败原因。
            Map<String, String> errorData = new HashMap<>(); // error 也使用 JSON 对象，保持前端解析一致。
            errorData.put("message", resolveClientErrorMessage(error)); // DeepSeek等内部异常转成友好文案，不把原始HTTP错误暴露给前端。
            emitter.send(SseEmitter.event().name("error").data(errorData));
        } catch (IOException ignored) {
            // 连接断开时忽略二次发送失败。
        }
        emitter.completeWithError(error);
    }

    private String resolveClientErrorMessage(Throwable error) {
        String message = error == null ? null : error.getMessage(); // 读取原始异常信息。
        if (message != null && (message.contains("DeepSeek调用失败")
                || message.contains("DeepSeek流式调用失败")
                || message.contains("HTTP 400"))) { // 模型调用异常对前端统一兜底。
            return "模型调用失败，请稍后重试。"; // 友好错误，不暴露内部HTTP细节。
        }
        return message == null || message.trim().isEmpty() ? "请求处理失败，请稍后重试。" : message; // 其它业务异常保持原友好提示。
    }
}

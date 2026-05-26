package com.agent.service.impl;

import com.agent.entity.ConversationMemory;
import com.agent.mapper.ConversationMemoryMapper;
import com.agent.service.ConversationMemoryService;
import com.agent.toolcalling.client.DeepSeekClient;
import com.agent.toolcalling.config.DeepSeekProperties;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 会话级长期记忆服务实现。
 *
 * <p>适用场景：负责 conversation_memory 表的基础读写，以及在每轮聊天完成后调用 DeepSeek 非流式接口生成新的会话长期摘要。</p>
 * <p>当前调用链：ChatMessageServiceImpl 的 Tool Calling SSE 回答完成 -> ConversationMemoryService.updateMemoryAfterChat
 * -> ConversationMemoryServiceImpl -> DeepSeekClient.chatCompletions -> ConversationMemoryMapper -> conversation_memory 表；ConversationServiceImpl 删除会话时调用本类清理对应 memory。</p>
 * <p>边界说明：本类只写入长期记忆，不读取长期记忆参与回答；不修改数据库结构，不执行建表 SQL，不影响 /chat/message SSE 返回。</p>
 */
@Slf4j // 输出 [ConversationMemory] 前缀日志，便于排查长期记忆读写和摘要生成行为。
@Service // 注册为 Spring Bean，供 ChatMessageServiceImpl 注入使用。
public class ConversationMemoryServiceImpl implements ConversationMemoryService { // 会话长期记忆服务实现类。

    private static final int MEMORY_SUMMARY_MAX_LENGTH = 800; // 长期记忆摘要最多保留 800 字，避免 summary 无限膨胀。

    private static final int PREVIEW_MAX_LENGTH = 80; // 日志 preview 最多 80 字，避免打印完整摘要或回答。

    private static final String[] MEMORY_SKIP_KEYWORDS = { // 明显异常、拒答、失败类 assistant 回复不写入长期记忆。
            "系统错误",
            "调用失败",
            "工具未注册",
            "DeepSeek调用失败",
            "DeepSeek 调用失败",
            "RuntimeException",
            "Exception",
            "invalid_request_error",
            "敏感词",
            "高频敏感词",
            "无法为您提供回答",
            "无法提供回答",
            "接口异常",
            "服务异常",
            "请求失败",
            "超时",
            "timeout",
            "网络异常",
            "连接失败"
    };

    @Autowired // 注入 MyBatis-Plus Mapper，负责 conversation_memory 表访问。
    private ConversationMemoryMapper conversationMemoryMapper; // conversation_memory 表基础 CRUD 入口。

    @Autowired // 复用 Tool 模块 DeepSeek 非流式客户端生成长期摘要。
    private DeepSeekClient deepSeekClient; // 只在 updateMemoryAfterChat 中用于摘要生成，不影响聊天流式链路。

    @Autowired // 读取 deepseek.model-name，保持模型配置来源统一。
    private DeepSeekProperties deepSeekProperties; // DeepSeek 模型配置。

    private final ObjectMapper objectMapper = new ObjectMapper(); // 构造 DeepSeek chat/completions 请求体。

    @Override // 实现按会话和用户查询长期记忆。
    public ConversationMemory getByConversationAndUser(Long conversationId, Long userId) {
        validateConversationAndUser(conversationId, userId); // 所有读写必须明确会话和用户，避免越权或脏数据。
        log.info("[ConversationMemory] get memory, conversationId: {}, userId: {}", conversationId, userId); // 只打印 ID，不打印 summary。
        return conversationMemoryMapper.selectOne(new LambdaQueryWrapper<ConversationMemory>() // 根据唯一索引 uk_conversation_user 查询。
                .eq(ConversationMemory::getConversationId, conversationId) // 限定会话 ID。
                .eq(ConversationMemory::getUserId, userId) // 限定用户 ID，保证用户隔离。
                .last("LIMIT 1")); // 表已有唯一索引，这里兜底限制只返回一条。
    }

    @Override // 实现获取或初始化长期记忆。
    public ConversationMemory getOrCreate(Long conversationId, Long userId) {
        validateConversationAndUser(conversationId, userId); // 创建前同样校验关键归属参数。
        ConversationMemory existingMemory = getByConversationAndUser(conversationId, userId); // 先查已有记录，避免重复插入。
        if (existingMemory != null) {
            return existingMemory; // 已存在则直接复用，不修改原记录。
        }

        LocalDateTime now = LocalDateTime.now(); // 创建和更新时间使用同一个时间点。
        ConversationMemory memory = new ConversationMemory(); // 构造新的空长期记忆记录。
        memory.setConversationId(conversationId); // 写入会话归属。
        memory.setUserId(userId); // 写入用户归属。
        memory.setSummary(""); // 初始摘要为空字符串，避免 null 影响后续拼接或展示。
        memory.setMessageCount(0); // 初始已汇总消息数为 0。
        memory.setCreateTime(now); // 设置创建时间。
        memory.setUpdateTime(now); // 设置更新时间。
        log.info("[ConversationMemory] create memory, conversationId: {}, userId: {}", conversationId, userId); // 创建日志不打印 summary。
        conversationMemoryMapper.insert(memory); // 插入 conversation_memory 表，id 由数据库自增生成。
        return memory; // 返回带自增 id 的新记录。
    }

    @Override // 实现摘要和消息计数更新。
    public void updateSummary(Long conversationId, Long userId, String summary, Integer messageCount) {
        validateConversationAndUser(conversationId, userId); // 更新前校验会话和用户归属。
        ConversationMemory memory = getOrCreate(conversationId, userId); // 没有长期记忆时先创建空记录。
        Integer resolvedMessageCount = messageCount != null // 传入计数优先。
                ? messageCount // 使用调用方传入的新 messageCount。
                : (memory.getMessageCount() == null ? 0 : memory.getMessageCount()); // 未传时保留原值，原值为空则兜底为 0。
        memory.setSummary(summary == null ? "" : summary); // summary 为 null 时写空字符串，避免数据库或后续读取出现 null。
        memory.setMessageCount(resolvedMessageCount); // 更新已汇总消息数量。
        memory.setUpdateTime(LocalDateTime.now()); // 每次摘要更新都刷新更新时间。
        log.info("[ConversationMemory] update memory, conversationId: {}, userId: {}, messageCount: {}",
                conversationId, userId, resolvedMessageCount); // 更新日志只打印计数，不打印完整 summary。
        conversationMemoryMapper.updateById(memory); // 根据主键更新当前长期记忆记录。
    }

    @Override // 在一轮聊天完成后更新长期记忆摘要。
    public void updateMemoryAfterChat(Long conversationId,
                                      Long userId,
                                      String userMessage,
                                      String assistantAnswer) {
        try { // 长期记忆失败不能影响 SSE 主流程，所以本方法内部兜底所有异常。
            validateConversationAndUser(conversationId, userId); // conversationId/userId 缺失属于非法调用，但仍会被本方法 catch。
            if (isBlank(userMessage) || isBlank(assistantAnswer)) {
                log.info("[ConversationMemory] skip memory update, empty message or answer"); // 空输入或空回答没有长期记忆价值。
                return; // 直接跳过，不写入 summary。
            }
            if (shouldSkipMemoryUpdate(userMessage, assistantAnswer)) {
            log.debug("[ConversationMemory] skip memory update, dirty assistant answer preview: {}",
                        previewContent(assistantAnswer)); // 异常/拒答类回复不污染长期记忆。
                return; // 脏回答不更新 memory。
            }

            log.info("[ConversationMemory] update after chat, conversationId: {}, userId: {}", conversationId, userId); // 标记聊天完成后开始更新长期记忆。
            ConversationMemory memory = getOrCreate(conversationId, userId); // 读取或创建当前会话用户的长期记忆记录。
            int oldMessageCount = memory.getMessageCount() == null ? 0 : memory.getMessageCount(); // 原计数为空时兜底为 0。
            log.debug("[ConversationMemory] old message count: {}", oldMessageCount); // 计数细节降级为DEBUG。

            ObjectNode requestBody = buildMemorySummaryRequest(memory.getSummary(), userMessage, assistantAnswer); // 用旧摘要和本轮问答构造摘要请求。
            log.debug("[ConversationMemory] summary prompt built"); // prompt构造细节降级为DEBUG。
            JsonNode response = deepSeekClient.chatCompletions(requestBody); // 非流式调用 DeepSeek 生成新的长期摘要。
            String newSummary = normalizeSummary(readSummaryContent(response)); // 解析并限制摘要长度。
            if (isBlank(newSummary)) {
                log.warn("[ConversationMemory] skip memory update, empty summary response"); // 模型返回空摘要时不覆盖旧 summary。
                return; // 避免把已有摘要清空。
            }

            int newMessageCount = oldMessageCount + 2; // 本轮包含一条 user 消息和一条 assistant 消息。
            memory.setSummary(newSummary); // 写入新的长期摘要正文。
            memory.setMessageCount(newMessageCount); // 更新已汇总消息数量。
            memory.setUpdateTime(LocalDateTime.now()); // 刷新长期记忆更新时间。
            conversationMemoryMapper.updateById(memory); // 根据主键更新 conversation_memory。

            log.debug("[ConversationMemory] new message count: {}", newMessageCount); // 计数细节降级为DEBUG。
            log.info("[ConversationMemory] summary updated"); // 标记 summary 已成功更新。
            log.debug("[ConversationMemory] summary preview: {}", previewContent(newSummary)); // summary preview不能在INFO打印。
        } catch (Exception e) {
            log.warn("[ConversationMemory] update memory failed, conversationId: {}, userId: {}, error: {}",
                    conversationId, userId, e.getMessage(), e); // 长期记忆异常只记录，不向外抛出影响聊天结果。
        }
    }

    @Override // 删除指定用户指定会话的长期记忆。
    public void deleteByConversationAndUser(Long conversationId, Long userId) {
        validateConversationAndUser(conversationId, userId); // 删除长期记忆必须同时限定会话和用户，避免误删其它用户数据。
        log.info("[ConversationMemory] delete memory, conversationId: {}, userId: {}", conversationId, userId); // 删除日志只打印归属 ID。
        conversationMemoryMapper.delete(new LambdaQueryWrapper<ConversationMemory>() // 按 uk_conversation_user 对应条件删除，记录不存在时 MyBatis-Plus 返回0且不报错。
                .eq(ConversationMemory::getConversationId, conversationId) // 限定会话 ID。
                .eq(ConversationMemory::getUserId, userId)); // 限定用户 ID，禁止只按 conversationId 删除。
    }

    private ObjectNode buildMemorySummaryRequest(String oldSummary, String userMessage, String assistantAnswer) {
        ObjectNode request = objectMapper.createObjectNode(); // 创建 DeepSeek chat/completions 请求体。
        request.put("model", deepSeekProperties.getModelName()); // 模型名沿用 deepseek.model-name 配置。
        ObjectNode thinking = objectMapper.createObjectNode(); // 创建 thinking 配置节点。
        thinking.put("type", "disabled"); // 摘要任务不需要 reasoning_content，保持与现有 Tool Calling 请求一致。
        request.set("thinking", thinking); // 写入 thinking 配置。

        ArrayNode messages = objectMapper.createArrayNode(); // 创建 messages 数组。
        messages.add(buildMessage("system", buildMemorySummarySystemPrompt())); // 写入摘要器系统约束。
        messages.add(buildMessage("user", buildMemorySummaryUserPrompt(oldSummary, userMessage, assistantAnswer))); // 写入旧摘要和本轮问答。
        request.set("messages", messages); // 设置 DeepSeek 对话消息。
        return request; // 返回可直接传给 DeepSeekClient.chatCompletions 的请求体。
    }

    private ObjectNode buildMessage(String role, String content) {
        ObjectNode message = objectMapper.createObjectNode(); // 创建单条 OpenAI-compatible message。
        message.put("role", role); // 写入 message 角色。
        message.put("content", content); // 写入 message 内容。
        return message; // 返回 message JSON。
    }

    private String buildMemorySummarySystemPrompt() {
        return "你是 Tech-Brain 项目的会话长期记忆摘要器。" + // 明确模型当前任务不是回答用户，而是更新长期摘要。
                "\n请基于旧摘要、本轮用户消息、本轮助手回复，生成新的长期记忆摘要。" +
                "\n摘要要求：" +
                "\n- 保留对后续对话有价值的信息。" +
                "\n- 保留用户明确关注的主题。" +
                "\n- 保留已回答的重要结论。" +
                "\n- 保留代码、项目、技术方案相关关键点。" +
                "\n- 不要记录无意义寒暄。" +
                "\n- 不要记录系统错误、接口异常、敏感词拒答。" +
                "\n- 不要超过 800 字。" +
                "\n- 用中文输出。" +
                "\n- 只输出摘要正文，不要输出标题和解释。";
    }

    private String buildMemorySummaryUserPrompt(String oldSummary, String userMessage, String assistantAnswer) {
        return "旧长期记忆摘要：\n" + valueOrEmpty(oldSummary) + // 旧 summary 作为模型合并基础。
                "\n\n本轮用户消息：\n" + valueOrEmpty(userMessage) + // 当前原始用户输入，不使用 multiTurnQuestion。
                "\n\n本轮助手回复：\n" + valueOrEmpty(assistantAnswer) + // 完整 assistant 回复，用于抽取已回答结论。
                "\n\n请输出更新后的长期记忆摘要正文。";
    }

    private String readSummaryContent(JsonNode response) {
        if (response == null) {
            return ""; // DeepSeek 返回空对象时视为无摘要。
        }
        return response.path("choices").path(0).path("message").path("content").asText(""); // 读取 OpenAI-compatible content 字段。
    }

    private String normalizeSummary(String summary) {
        if (summary == null) {
            return ""; // null 摘要统一转为空字符串。
        }
        String normalizedSummary = summary.trim(); // 去掉模型输出前后空白。
        if (normalizedSummary.length() <= MEMORY_SUMMARY_MAX_LENGTH) {
            return normalizedSummary; // 800 字以内直接使用。
        }
        return normalizedSummary.substring(0, MEMORY_SUMMARY_MAX_LENGTH); // 超过 800 字时后端强制截断，避免长期膨胀。
    }

    private boolean shouldSkipMemoryUpdate(String userMessage, String assistantAnswer) {
        if (isBlank(assistantAnswer)) {
            return true; // 空 assistant 回复不进入长期记忆。
        }
        String lowerAssistantAnswer = assistantAnswer.toLowerCase(); // 英文 timeout/Exception 等按小写兼容大小写。
        for (String keyword : MEMORY_SKIP_KEYWORDS) {
            if (lowerAssistantAnswer.contains(keyword.toLowerCase())) {
                return true; // 命中异常、失败、拒答关键词时跳过长期记忆更新。
            }
        }
        return false; // 未命中脏内容则允许更新。
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value.trim(); // prompt 中避免出现 null 字面量。
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty(); // 兼容低风险空白判断。
    }

    private String previewContent(String content) {
        if (content == null) {
            return ""; // null 内容日志预览为空。
        }
        String preview = content.replace('\n', ' ').replace('\r', ' '); // 去掉换行，避免日志多行膨胀。
        return preview.length() <= PREVIEW_MAX_LENGTH ? preview : preview.substring(0, PREVIEW_MAX_LENGTH) + "..."; // 最多打印 80 字。
    }

    private void validateConversationAndUser(Long conversationId, Long userId) {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId不能为空"); // conversationId 是唯一索引的一部分，不能为空。
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId不能为空"); // userId 是用户隔离边界，不能为空。
        }
    }
}

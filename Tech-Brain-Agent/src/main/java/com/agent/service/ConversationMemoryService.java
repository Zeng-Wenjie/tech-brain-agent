package com.agent.service;

import com.agent.entity.ConversationMemory;

/**
 * 会话级长期记忆服务接口。
 *
 * <p>适用场景：为后续长期记忆能力提供统一读写入口，屏蔽 Mapper 细节。</p>
 * <p>当前调用链：ChatMessageServiceImpl 在 assistant 完整回复保存后 -> ConversationMemoryService
 * -> ConversationMemoryServiceImpl -> DeepSeekClient 非流式摘要 -> ConversationMemoryMapper。</p>
 * <p>本接口只负责 conversation_memory 的长期记忆读写，不参与 Tool Calling 路由，不把长期记忆传入模型回答。</p>
 */
public interface ConversationMemoryService { // 会话长期记忆业务接口。

    ConversationMemory getByConversationAndUser(Long conversationId, Long userId); // 按会话和用户查询一条长期记忆。

    ConversationMemory getOrCreate(Long conversationId, Long userId); // 不存在时创建空长期记忆并返回。

    void updateSummary(Long conversationId, Long userId, String summary, Integer messageCount); // 更新长期摘要和已汇总消息数量。

    void updateMemoryAfterChat(Long conversationId,
                               Long userId,
                               String userMessage,
                               String assistantAnswer); // 一轮聊天完成后基于用户输入和助手回复更新长期记忆摘要。
}

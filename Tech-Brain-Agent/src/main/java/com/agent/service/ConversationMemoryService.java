package com.agent.service;

import com.agent.entity.ConversationMemory;

/**
 * 会话级长期记忆服务接口。
 *
 * <p>适用场景：为后续长期记忆能力提供统一读写入口，屏蔽 Mapper 细节。</p>
 * <p>当前调用链：未来业务层/聊天服务 -> ConversationMemoryService -> ConversationMemoryServiceImpl -> ConversationMemoryMapper。</p>
 * <p>当前阶段只定义基础查询、获取或创建、摘要更新方法；不接入 /chat/message，不调用 DeepSeek，不做自动总结。</p>
 */
public interface ConversationMemoryService { // 会话长期记忆业务接口。

    ConversationMemory getByConversationAndUser(Long conversationId, Long userId); // 按会话和用户查询一条长期记忆。

    ConversationMemory getOrCreate(Long conversationId, Long userId); // 不存在时创建空长期记忆并返回。

    void updateSummary(Long conversationId, Long userId, String summary, Integer messageCount); // 更新长期摘要和已汇总消息数量。
}

package com.agent.service.impl;

import com.agent.entity.ConversationMemory;
import com.agent.mapper.ConversationMemoryMapper;
import com.agent.service.ConversationMemoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 会话级长期记忆服务实现。
 *
 * <p>适用场景：负责 conversation_memory 表的基础读写，包括按 conversationId + userId 查询、缺失时初始化、更新摘要和 messageCount。</p>
 * <p>当前调用链：ConversationMemoryService 调用方 -> ConversationMemoryServiceImpl -> ConversationMemoryMapper -> conversation_memory 表。</p>
 * <p>本类当前不接入 ChatMessageServiceImpl，不参与 POST /chat/message 主链路，不调用 DeepSeek，不做自动总结。</p>
 * <p>边界说明：本类只做 Java 层基础读写和参数校验，不执行建表 SQL，不修改数据库结构，不打印完整 summary。</p>
 */
@Slf4j // 输出 [ConversationMemory] 前缀日志，便于后续接入长期记忆时排查读写行为。
@Service // 注册为 Spring Bean，供后续业务服务注入使用。
public class ConversationMemoryServiceImpl implements ConversationMemoryService { // 会话长期记忆服务实现类。

    @Autowired // 注入 MyBatis-Plus Mapper，负责 conversation_memory 表访问。
    private ConversationMemoryMapper conversationMemoryMapper; // conversation_memory 表基础 CRUD 入口。

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

    private void validateConversationAndUser(Long conversationId, Long userId) {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId不能为空"); // conversationId 是唯一索引的一部分，不能为空。
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId不能为空"); // userId 是用户隔离边界，不能为空。
        }
    }
}

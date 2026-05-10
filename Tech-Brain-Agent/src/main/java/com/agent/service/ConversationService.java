package com.agent.service;

import com.agent.entity.ChatMessage;
import com.agent.entity.Conversation;
import com.agent.entity.Result;

import java.util.List;
/*
    负责会话列表和历史记录查询
 */

public interface ConversationService {

    Result<Long> createConversation();

    Result<List<Conversation>> listConversations();

    Result<List<ChatMessage>> listMessages(Long conversationId);
}

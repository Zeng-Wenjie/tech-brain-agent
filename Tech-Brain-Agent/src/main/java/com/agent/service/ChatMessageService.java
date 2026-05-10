package com.agent.service;

import com.agent.entity.dto.ChatRequestDTO;
import com.agent.entity.dto.ChatResponseDTO;
/*
    负责会话持久化和消息保存
 */

public interface ChatMessageService {

    ChatResponseDTO sendMessage(ChatRequestDTO dto);
}

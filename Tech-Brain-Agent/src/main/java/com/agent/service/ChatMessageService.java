package com.agent.service;

import com.agent.entity.dto.ChatRequestDTO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
/*
    负责会话持久化和消息保存
 */

public interface ChatMessageService {

    SseEmitter sendMessage(ChatRequestDTO dto);
}

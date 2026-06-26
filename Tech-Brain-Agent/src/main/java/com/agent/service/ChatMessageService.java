package com.agent.service;

import com.agent.entity.dto.ChatRequestDTO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
/*
    负责会话持久化和消息保存
 */

public interface ChatMessageService {

    SseEmitter sendMessage(ChatRequestDTO dto); // /chat/message：智能体模式，允许 Tool Calling 工具路由。

    SseEmitter sendMessage(ChatRequestDTO dto, boolean plainChat); // /chat/plain：plainChat=true 时为普通聊天，关闭所有工具，避免误触工具调用。
}

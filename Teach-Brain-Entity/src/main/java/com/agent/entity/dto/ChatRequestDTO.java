package com.agent.entity.dto;

import lombok.Data;

@Data
public class ChatRequestDTO {

    // 为空时自动创建新会话。
    private Long conversationId;

    // 用户本轮提问内容。
    private String msg;
}

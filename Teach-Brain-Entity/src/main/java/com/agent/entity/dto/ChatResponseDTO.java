package com.agent.entity.dto;

import lombok.Data;

@Data
public class ChatResponseDTO {

    // 本轮消息所属会话ID。
    private Long conversationId;

    // AI回复内容。
    private String answer;
}

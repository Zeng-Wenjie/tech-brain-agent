package com.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    // 消息主键。
    private Long id;

    // 所属会话ID。
    private Long conversationId;

    // 消息所属用户。
    private Long userId;

    // 消息角色：user 或 assistant。
    private String role;

    // 消息正文。
    private String content;

    // 消息创建时间。
    private LocalDateTime createTime;
}

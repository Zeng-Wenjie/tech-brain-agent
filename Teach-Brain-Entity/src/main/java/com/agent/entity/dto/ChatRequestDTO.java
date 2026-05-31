package com.agent.entity.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 聊天消息请求参数。
 *
 * <p>适用场景：承载前端 {@code POST /chat/message} 的单轮聊天请求，包括会话 ID、用户输入和本轮可选附件文件 ID 列表。</p>
 * <p>调用链：ChatMessageController -> ChatMessageServiceImpl -> ToolCallingRequestContext -> ToolCallingChatServiceImpl。</p>
 * <p>边界说明：本 DTO 只接收 fileIds，不接收 userId，不包含文件内容或 storagePath；文件权限由后端 Service 校验。</p>
 */
@Data
@Schema(description = "聊天消息请求参数")
public class ChatRequestDTO { // /chat/message 请求体。

    @Schema(description = "会话 ID，为空时自动创建新会话", example = "1")
    // 为空时自动创建新会话。
    private Long conversationId;

    @JsonAlias("message") // 兼容前端或调试工具传 message 字段，内部仍沿用现有 msg 字段。
    @Schema(description = "用户本轮提问内容", example = "你好")
    // 用户本轮提问内容。
    private String msg;

    @Schema(description = "本轮聊天附带的用户文件 ID 列表", example = "[4,5]")
    private List<Long> fileIds; // 可为空；Service 层按当前登录 userId 校验文件归属。
}

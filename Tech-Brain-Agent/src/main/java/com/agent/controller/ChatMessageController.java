package com.agent.controller;

import com.agent.entity.Result;
import com.agent.entity.dto.ChatRequestDTO;
import com.agent.entity.dto.ChatResponseDTO;
import com.agent.service.ChatMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "AI聊天消息接口")
public class ChatMessageController {

    @Autowired
    private ChatMessageService chatMessageService;

    @Operation(summary = "发送会话消息")
    @PostMapping("/chat/message")
    public Result<ChatResponseDTO> sendMessage(@RequestBody ChatRequestDTO dto) {
        if (dto == null || dto.getMsg() == null || dto.getMsg().trim().isEmpty()) {
            return Result.error(HttpServletResponse.SC_BAD_REQUEST, "消息内容不能为空");
        }
        try {
            return Result.success(chatMessageService.sendMessage(dto));
        } catch (IllegalArgumentException e) {
            return Result.error(HttpServletResponse.SC_FORBIDDEN, e.getMessage());
        }
    }
}

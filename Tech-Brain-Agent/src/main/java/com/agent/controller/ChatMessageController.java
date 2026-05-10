package com.agent.controller;

import com.agent.entity.dto.ChatRequestDTO;
import com.agent.service.ChatMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Tag(name = "AI聊天消息接口")
public class ChatMessageController {

    @Autowired
    private ChatMessageService chatMessageService;

    @Operation(summary = "发送会话消息")
    @PostMapping(value = "/chat/message", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(@RequestBody ChatRequestDTO dto) {
        return chatMessageService.sendMessage(dto);
    }
}

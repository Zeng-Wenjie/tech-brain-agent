package com.agent.controller;

import com.agent.entity.ChatMessage;
import com.agent.entity.Conversation;
import com.agent.entity.Result;
import com.agent.service.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/conversation")
@Tag(name = "AI聊天会话接口")
public class ConversationController {

    @Autowired
    private ConversationService conversationService;

    @Operation(summary = "创建聊天会话")
    @PostMapping
    public Result<Long> createConversation() {
        return conversationService.createConversation();
    }

    @Operation(summary = "查询当前用户会话列表")
    @GetMapping("/list")
    public Result<List<Conversation>> listConversations() {
        return conversationService.listConversations();
    }

    @Operation(summary = "查询指定会话消息")
    @GetMapping("/{id}/messages")
    public Result<List<ChatMessage>> listMessages(@PathVariable("id") Long conversationId) {
        return conversationService.listMessages(conversationId);
    }
}

package com.chatbot.controller;

import com.chatbot.dto.Dtos;
import com.chatbot.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /** 发送消息 */
    @PostMapping("/chat")
    public Dtos.ChatResponse chat(@Valid @RequestBody Dtos.ChatRequest request) {
        return chatService.chat(request);
    }

    /** 会话历史 */
    @GetMapping("/sessions/{sessionId}/messages")
    public List<Dtos.MessageDto> history(@PathVariable String sessionId) {
        return chatService.history(sessionId);
    }

    /** 清空会话 */
    @DeleteMapping("/sessions/{sessionId}")
    public void clear(@PathVariable String sessionId) {
        chatService.clear(sessionId);
    }
}

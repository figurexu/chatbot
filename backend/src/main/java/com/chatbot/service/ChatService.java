package com.chatbot.service;

import com.chatbot.dto.Dtos;
import com.chatbot.entity.CharacterEntity;
import com.chatbot.entity.MessageEntity;
import com.chatbot.llm.ChatLlmException;
import com.chatbot.llm.LlmClient;
import com.chatbot.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final CharacterService characterService;
    private final MessageRepository messageRepository;
    private final LlmClient llmClient;
    private final int maxHistory;

    public ChatService(CharacterService characterService,
                       MessageRepository messageRepository,
                       LlmClient llmClient,
                       @Value("${chatbot.history.max-history:20}") int maxHistory) {
        this.characterService = characterService;
        this.messageRepository = messageRepository;
        this.llmClient = llmClient;
        this.maxHistory = maxHistory;
    }

    /** 单轮对话：持久化用户消息与回复，返回回复内容 */
    @Transactional
    public Dtos.ChatResponse chat(Dtos.ChatRequest request) {
        CharacterEntity character = characterService.findEnabled(request.characterId());
        String sessionId = normalizeSession(request.sessionId(), request.characterId());

        List<MessageEntity> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<LlmClient.ChatMessage> llmHistory = new ArrayList<>();
        int from = Math.max(0, history.size() - maxHistory);
        for (int i = from; i < history.size(); i++) {
            MessageEntity m = history.get(i);
            llmHistory.add(new LlmClient.ChatMessage(m.getRole(), m.getContent()));
        }

        save(sessionId, character.getId(), "user", request.message());
        String reply;
        try {
            reply = llmClient.complete(character.getName(), character.getSystemPrompt(), llmHistory, request.message());
        } catch (ChatLlmException e) {
            // 保存失败原因供排查，但用户消息已入库，可重试
            log.error("LLM 调用失败 session={} character={}", sessionId, character.getId(), e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 服务暂不可用，请稍后重试: " + e.getMessage());
        }
        save(sessionId, character.getId(), "assistant", reply);
        return new Dtos.ChatResponse(sessionId, character.getId(), reply, Instant.now());
    }

    public List<Dtos.MessageDto> history(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(m -> new Dtos.MessageDto(m.getRole(), m.getContent(), m.getCreatedAt()))
                .toList();
    }

    @Transactional
    public void clear(String sessionId) {
        messageRepository.deleteBySessionId(sessionId);
    }

    private void save(String sessionId, String characterId, String role, String content) {
        MessageEntity msg = new MessageEntity();
        msg.setSessionId(sessionId);
        msg.setCharacterId(characterId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setCreatedAt(Instant.now());
        messageRepository.save(msg);
    }

    private String normalizeSession(String sessionId, String characterId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "s_" + characterId + "_" + UUID.randomUUID().toString().substring(0, 8);
        }
        return sessionId;
    }
}

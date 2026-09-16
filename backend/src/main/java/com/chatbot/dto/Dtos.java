package com.chatbot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** 对外 DTO 集合 */
public final class Dtos {

    private Dtos() {
    }

    public record CharacterDto(
            String id,
            String name,
            String dynasty,
            String title,
            String tagline,
            String avatar,
            String greeting
    ) {
    }

    public record MessageDto(
            String role,
            String content,
            Instant createdAt
    ) {
    }

    public record ChatRequest(
            @NotBlank(message = "characterId 不能为空") @Size(max = 32) String characterId,
            @NotBlank(message = "消息内容不能为空") @Size(max = 2000, message = "单条消息不能超过 2000 字") String message,
            @Size(max = 64) String sessionId
    ) {
    }

    public record ChatResponse(
            String sessionId,
            String characterId,
            String reply,
            Instant createdAt
    ) {
    }
}

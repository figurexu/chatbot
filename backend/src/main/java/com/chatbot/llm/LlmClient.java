package com.chatbot.llm;

import java.util.List;

/** 大模型客户端抽象：接入新的模型提供方只需实现本接口 */
public interface LlmClient {

    record ChatMessage(String role, String content) {
    }

    /**
     * 生成回复
     *
     * @param characterName 人物名（供 mock 使用）
     * @param systemPrompt  人物人设 system prompt
     * @param history       历史对话（user/assistant）
     * @param userMessage   用户当前消息
     * @return 模型回复文本
     */
    String complete(String characterName, String systemPrompt, List<ChatMessage> history, String userMessage);
}

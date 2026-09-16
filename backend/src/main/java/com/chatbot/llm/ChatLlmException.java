package com.chatbot.llm;

/** LLM 调用失败统一异常 */
public class ChatLlmException extends RuntimeException {

    public ChatLlmException(String message) {
        super(message);
    }

    public ChatLlmException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.chatbot.config;

import com.chatbot.service.StreamAsrRelay;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 注册：小程序流式语音识别中继端点。
 */
@Configuration
@EnableWebSocket
public class VoiceWsConfig implements WebSocketConfigurer {

    private final StreamAsrRelay streamAsrRelay;

    public VoiceWsConfig(StreamAsrRelay streamAsrRelay) {
        this.streamAsrRelay = streamAsrRelay;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(streamAsrRelay, "/api/voice/ws").setAllowedOrigins("*");
    }
}

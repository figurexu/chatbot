package com.chatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.WebSocketHttpHeaders;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流式语音识别中继：
 * 小程序 WebSocket → 本端点 → 火山引擎双向流式识别（/api/v3/sauc/bigmodel_async）。
 * 客户端协议（与小程序约定）：
 *   小程序→本服务：{"type":"start"|"end"|"cancel"} 文本帧；PCM 音频二进制帧
 *   本服务→小程序：{"type":"result","text":"..."} / {"type":"done","text":"..."} / {"type":"error","message":"..."}
 */
@Component
public class StreamAsrRelay extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamAsrRelay.class);

    /** 火山 SAUC 帧头消息类型 */
    private static final int MSG_FULL_REQUEST = 0x0;   // 全量 JSON 配置/响应
    private static final int MSG_AUDIO = 0x1;          // 音频帧
    private static final int MSG_FINAL = 0xF;          // 结束帧

    private final ObjectMapper objectMapper;

    @Value("${chatbot.voice.api-key:}")
    private String apiKey;

    @Value("${chatbot.voice.stream-resource-id:volc.seedasr.sauc.duration}")
    private String streamResourceId;

    @Value("${chatbot.voice.stream-endpoint:wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async}")
    private String streamEndpoint;

    private final Map<WebSocketSession, VolcanoLink> links = new ConcurrentHashMap<>();

    public StreamAsrRelay(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /* ==================== 小程序侧（本服务作为 WS 服务端） ==================== */

    @Override
    protected void handleTextMessage(WebSocketSession client, TextMessage message) throws Exception {
        JsonNode node;
        try {
            node = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            sendToClient(client, "error", "消息格式错误");
            return;
        }
        String type = node.path("type").asText("");
        switch (type) {
            case "start" -> connectVolcano(client);
            case "end" -> {
                VolcanoLink link = links.get(client);
                if (link != null) {
                    link.sendFrame(MSG_FINAL, new byte[0]);
                }
            }
            case "cancel" -> closeVolcano(client);
            default -> sendToClient(client, "error", "未知指令: " + type);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession client, BinaryMessage message) {
        VolcanoLink link = links.get(client);
        if (link == null) {
            return;
        }
        byte[] data = message.getPayload().array();
        if (data.length > 0) {
            link.sendFrame(MSG_AUDIO, data);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession client, CloseStatus status) {
        closeVolcano(client);
    }

    /* ==================== 火山侧（本服务作为 WS 客户端） ==================== */

    private void connectVolcano(WebSocketSession client) {
        closeVolcano(client); // 防重入
        if (!StringUtils.hasText(apiKey)) {
            sendToClient(client, "error", "语音服务未配置（缺少 CHATBOT_VOICE_API_KEY）");
            return;
        }
        try {
            StandardWebSocketClient wsClient = new StandardWebSocketClient();
            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            headers.add("X-Api-Key", apiKey);
            headers.add("X-Api-Resource-Id", streamResourceId);
            headers.add("X-Api-Request-Id", UUID.randomUUID().toString());
            VolcanoLink link = new VolcanoLink(client);
            links.put(client, link);
            wsClient.execute(link, headers, URI.create(streamEndpoint));
        } catch (Exception e) {
            log.error("连接火山流式识别失败", e);
            links.remove(client);
            sendToClient(client, "error", "连接语音识别服务失败");
        }
    }

    private void closeVolcano(WebSocketSession client) {
        VolcanoLink link = links.remove(client);
        if (link != null) {
            link.close();
        }
    }

    private void sendToClient(WebSocketSession client, String type, String text) {
        try {
            if (client.isOpen()) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("type", type);
                if (text != null) {
                    node.put("text", text);
                }
                client.sendMessage(new TextMessage(node.toString()));
            }
        } catch (Exception e) {
            log.warn("发送给小程序失败", e);
        }
    }

    /**
     * 与火山的单条连接。发送时按 SAUC 帧协议封装；接收时按"一消息一帧"解析。
     */
    private class VolcanoLink implements WebSocketHandler {

        private final WebSocketSession client;
        private volatile WebSocketSession volcano;
        private volatile String lastText = "";

        VolcanoLink(WebSocketSession client) {
            this.client = client;
        }

        void sendFrame(int msgType, byte[] payload) {
            WebSocketSession v = volcano;
            if (v == null || !v.isOpen()) {
                return;
            }
            try {
                v.sendMessage(new BinaryMessage(makeFrame(msgType, payload)));
            } catch (Exception e) {
                log.warn("发送音频到火山失败", e);
            }
        }

        void close() {
            WebSocketSession v = volcano;
            if (v != null) {
                try {
                    v.close();
                } catch (Exception ignored) {
                }
            }
        }

        /* ---- 火山 WS 生命周期 ---- */

        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            this.volcano = session;
            ObjectNode payload = objectMapper.createObjectNode();
            ObjectNode user = payload.putObject("user");
            user.put("uid", "chatbot");
            ObjectNode audio = payload.putObject("audio");
            audio.put("format", "pcm");
            audio.put("codec", "raw");
            audio.put("rate", 16000);
            audio.put("bits", 16);
            audio.put("channel", 1);
            ObjectNode request = payload.putObject("request");
            request.put("model_name", "bigmodel");
            request.put("enable_itn", true);
            request.put("enable_punc", true);
            request.put("enable_ddc", true);
            session.sendMessage(new BinaryMessage(makeFrame(MSG_FULL_REQUEST, payload.toString().getBytes(StandardCharsets.UTF_8))));
            log.info("火山流式识别已连接");
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
            if (!(message instanceof BinaryMessage bm)) {
                return;
            }
            byte[] data = bm.getPayload().array();
            if (data.length < 21) {
                return;
            }
            int headerSize = readInt(data, 1);
            if (data.length < headerSize) {
                return;
            }
            int msgType = readInt(data, 5);
            byte[] payload = Arrays.copyOfRange(data, headerSize, data.length);
            if (msgType == MSG_FULL_REQUEST) {
                handleVolcanoJson(payload);
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.warn("火山流式连接异常: {}", exception.getMessage());
            sendToClient(client, "error", "语音识别服务连接中断");
            links.remove(client);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
            log.info("火山流式连接关闭: {}", closeStatus);
        }

        @Override
        public boolean supportsPartialMessages() {
            return false;
        }

        private void handleVolcanoJson(byte[] payload) {
            try {
                JsonNode root = objectMapper.readTree(payload);
                int code = root.path("code").asInt(-1);
                if (code != 0) {
                    String msg = root.path("message").asText("识别失败");
                    log.warn("火山流式返回错误: code={} msg={}", code, msg);
                    sendToClient(client, "error", "识别失败: " + msg);
                    return;
                }
                JsonNode result = root.path("payload_msg").path("result");
                String text = result.path("text").asText("");
                if (!text.isEmpty()) {
                    lastText = text;
                    sendToClient(client, "result", text);
                }
                if (root.path("is_last_package").asBoolean(false)) {
                    sendToClient(client, "done", lastText);
                }
            } catch (Exception e) {
                log.warn("解析火山流式响应失败", e);
            }
        }
    }

    /* ==================== SAUC 帧协议 ==================== */

    /** 构造火山 SAUC 帧：21 字节头 + payload */
    static byte[] makeFrame(int msgType, byte[] payload) {
        byte[] frame = new byte[21 + (payload == null ? 0 : payload.length)];
        frame[0] = 0b0001; // protocol_version
        writeInt(frame, 1, 21);      // header_size
        writeInt(frame, 5, msgType); // message_type
        writeInt(frame, 9, 0);       // message_type_specific_flags
        writeInt(frame, 13, 0);      // serialization_method: JSON
        writeInt(frame, 17, 0);      // message_compression: none
        if (payload != null && payload.length > 0) {
            System.arraycopy(payload, 0, frame, 21, payload.length);
        }
        return frame;
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }
}

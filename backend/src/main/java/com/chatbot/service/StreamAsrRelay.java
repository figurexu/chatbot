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
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流式语音识别中继：
 * 小程序 WebSocket → 本端点 → 火山引擎大模型流式语音识别（/api/v3/sauc/bigmodel_async）。
 *
 * 火山 SAUC 二进制帧（大端）：
 *   Header 4B：byte0=[protocol_version 4bit][header_size 4bit=0001]，byte1=[message_type 4bit][flags 4bit]，
 *              byte2=[serialization 4bit][compression 4bit]，byte3=reserved
 *   full client request / audio only request：Header(4) + Payload size(4) + Payload
 *   full server response：Header(4) + Sequence(4) + Payload size(4) + Payload
 *   error：Header(4) + Error code(4) + Error size(4) + Error message
 *   message_type：0x1=full client request，0x2=audio only，0x9=full server response，0xF=error
 *   audio 最后包 flags=0x2（不带 sequence，仅指示最后一包）；server 最终响应 flags=0x3
 *
 * 小程序侧协议：
 *   小程序→本服务：{"type":"start"|"end"|"cancel"} 文本帧；PCM(16k/16bit/mono) 二进制帧
 *   本服务→小程序：{"type":"result","text":...} / {"type":"done","text":...} / {"type":"error","message":...}
 */
@Component
public class StreamAsrRelay extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamAsrRelay.class);

    /** 火山消息类型（header 高 4 位） */
    private static final int MSG_FULL_REQUEST = 0x1;   // 全量配置
    private static final int MSG_AUDIO = 0x2;          // 音频帧
    private static final int MSG_SERVER_RESPONSE = 0x9; // 服务端识别结果
    private static final int MSG_ERROR = 0xF;          // 服务端错误帧

    /** flags：audio 最后一包；server 最终响应 */
    private static final int FLAG_LAST_AUDIO = 0x2;
    private static final int FLAG_FINAL_RESPONSE = 0x3;

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
    public void afterConnectionEstablished(WebSocketSession client) throws Exception {
        log.info("小程序 WS 已连接: {}", client.getId());
        super.afterConnectionEstablished(client);
    }

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
        log.info("收到指令 type={}", type);
        switch (type) {
            case "start" -> connectVolcano(client);
            case "end" -> {
                VolcanoLink link = links.get(client);
                if (link != null) {
                    link.sendLastAudio();
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
            link.sendAudio(data);
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
            headers.add("X-Api-Connect-Id", UUID.randomUUID().toString());
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
     * 与火山的单条连接。
     */
    private class VolcanoLink implements WebSocketHandler {

        private final WebSocketSession client;
        private volatile WebSocketSession volcano;
        private volatile String lastText = "";
        private volatile boolean finished = false;

        VolcanoLink(WebSocketSession client) {
            this.client = client;
        }

        void sendAudio(byte[] payload) {
            sendFrame(MSG_AUDIO, 0x0, payload);
        }

        void sendLastAudio() {
            sendFrame(MSG_AUDIO, FLAG_LAST_AUDIO, new byte[0]);
        }

        void sendFrame(int msgType, int flags, byte[] payload) {
            WebSocketSession v = volcano;
            if (v == null || !v.isOpen()) {
                return;
            }
            try {
                v.sendMessage(new BinaryMessage(makeFrame(msgType, flags, payload)));
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
            session.sendMessage(new BinaryMessage(makeFrame(MSG_FULL_REQUEST, 0x0,
                    payload.toString().getBytes(StandardCharsets.UTF_8))));
            log.info("火山流式识别已连接，配置已发送");
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
            if (!(message instanceof BinaryMessage bm)) {
                return;
            }
            byte[] data = bm.getPayload().array();
            if (data.length < 8) {
                return;
            }
            int msgType = (data[1] >> 4) & 0xF;
            int flags = data[1] & 0xF;
            if (msgType == MSG_SERVER_RESPONSE) {
                // Header(4) + Sequence(4) + Payload size(4) + Payload
                if (data.length < 12) {
                    return;
                }
                int payloadSize = readInt(data, 8);
                if (data.length < 12 + payloadSize) {
                    return;
                }
                byte[] payload = Arrays.copyOfRange(data, 12, 12 + payloadSize);
                handleVolcanoJson(payload, flags);
            } else if (msgType == MSG_ERROR) {
                // Header(4) + Error code(4) + Error size(4) + Error message
                if (data.length < 12) {
                    return;
                }
                int errSize = readInt(data, 8);
                String errMsg = new String(Arrays.copyOfRange(data, 12,
                        Math.min(data.length, 12 + errSize)), StandardCharsets.UTF_8);
                log.warn("火山流式错误帧: code={} msg={}", readInt(data, 4), errMsg);
                sendToClient(client, "error", "识别服务错误: " + errMsg);
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.warn("火山流式连接异常: {}", exception == null ? "unknown" : exception.getMessage());
            sendToClient(client, "error", "语音识别服务连接中断");
            links.remove(client);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
            log.info("火山流式连接关闭: code={} reason={}", closeStatus == null ? -1 : closeStatus.getCode(),
                    closeStatus == null ? "" : closeStatus.getReason());
            if (!finished) {
                sendToClient(client, "error", "语音识别服务未开通或连接被关闭");
            }
            links.remove(client);
        }

        @Override
        public boolean supportsPartialMessages() {
            return false;
        }

        private void handleVolcanoJson(byte[] payload, int flags) {
            try {
                JsonNode root = objectMapper.readTree(payload);
                JsonNode result = root.path("result");
                String text = result.path("text").asText("");
                if (!text.isEmpty()) {
                    lastText = text;
                    sendToClient(client, "result", text);
                }
                if (flags == FLAG_FINAL_RESPONSE) {
                    finished = true;
                    sendToClient(client, "done", lastText);
                }
            } catch (Exception e) {
                log.warn("解析火山流式响应失败", e);
            }
        }
    }

    /* ==================== SAUC 帧编解码（大端） ==================== */

    /** 构造请求帧：Header(4) + Payload size(4) + Payload */
    static byte[] makeFrame(int msgType, int flags, byte[] payload) {
        byte[] body = payload == null ? new byte[0] : payload;
        byte[] frame = new byte[8 + body.length];
        frame[0] = (byte) 0x11;                 // protocol_version=0001, header_size=0001(→4字节)
        frame[1] = (byte) ((msgType << 4) | (flags & 0xF));
        frame[2] = (byte) 0x10;                 // serialization=0001(JSON), compression=0000(none)
        frame[3] = 0x00;                        // reserved
        writeInt(frame, 4, body.length);        // payload size
        if (body.length > 0) {
            System.arraycopy(body, 0, frame, 8, body.length);
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

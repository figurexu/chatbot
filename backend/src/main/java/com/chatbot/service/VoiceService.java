package com.chatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语音能力（后端代理火山引擎豆包语音）：
 * - STT：录音文件识别 2.0（submit 提交公网音频 URL → query 轮询结果）
 * - TTS：语音合成 2.0（单向流式 HTTP，返回 base64 音频）
 * 新版控制台鉴权：请求头 x-api-key = API Key（AppID），无独立 token。
 */
@Service
public class VoiceService {

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

    /** ASR 提交/查询成功状态码 */
    private static final String ASR_OK = "20000000";
    private static final String ASR_PROCESSING = "20000001";
    private static final String ASR_QUEUED = "20000002";
    private static final String ASR_SILENCE = "20000003";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${chatbot.voice.api-key:}")
    private String apiKey;

    @Value("${chatbot.voice.asr-resource-id:volc.seedasr.auc}")
    private String asrResourceId;

    @Value("${chatbot.voice.asr-endpoint:https://openspeech.bytedance.com/api/v3/auc/bigmodel}")
    private String asrEndpoint;

    @Value("${chatbot.voice.tts-resource-id:seed-tts-2.0}")
    private String ttsResourceId;

    @Value("${chatbot.voice.tts-endpoint:https://openspeech.bytedance.com/api/v3/tts/unidirectional}")
    private String ttsEndpoint;

    @Value("${chatbot.voice.tts-speaker:zh_male_gaolengchenwen_uranus_bigtts}")
    private String ttsSpeaker;

    @Value("${chatbot.voice.public-base-url:}")
    private String publicBaseUrl;

    @Value("${chatbot.voice.audio-dir:/data/audio}")
    private String audioDir;

    /** 语音合成单次最大字符数 */
    private static final int TTS_MAX_CHARS = 500;

    /** TTS 结果缓存上限（条） */
    private static final int TTS_CACHE_MAX = 50;

    /** TTS 内存缓存：text -> mp3 字节，避免重复合成 */
    private final Map<String, byte[]> ttsCache = new ConcurrentHashMap<>();

    public VoiceService(RestClient llmRestClient, ObjectMapper objectMapper) {
        this.restClient = llmRestClient;
        this.objectMapper = objectMapper;
    }

    /* ==================== STT：录音文件识别 2.0 ==================== */

    /**
     * 识别录音文件。步骤：保存到本地 → 通过公网 Base URL 暴露 → submit → 轮询 query。
     */
    public String recognize(MultipartFile file, HttpServletRequest request) {
        if (apiKey.isBlank()) {
            throw new VoiceException("语音服务未配置（缺少 CHATBOT_VOICE_API_KEY）");
        }
        if (file == null || file.isEmpty()) {
            throw new VoiceException("录音文件为空");
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (!"mp3".equalsIgnoreCase(ext) && !"wav".equalsIgnoreCase(ext)
                && !"m4a".equalsIgnoreCase(ext) && !"ogg".equalsIgnoreCase(ext)) {
            throw new VoiceException("不支持的音频格式: " + ext);
        }

        // 1. 保存音频
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext.toLowerCase(Locale.ROOT);
        Path target = audioDirPath().resolve(fileName);
        try {
            Files.createDirectories(audioDirPath());
            file.transferTo(target);
        } catch (IOException e) {
            log.error("保存录音失败", e);
            throw new VoiceException("保存录音失败");
        }

        // 2. 构造公网音频 URL（默认取请求 Host，跟随隧道地址变化）
        String audioUrl = publicAudioUrl(request) + "/api/voice/audio/" + fileName;
        log.info("ASR submit audioUrl={}", audioUrl);

        // 3. submit
        String taskId = UUID.randomUUID().toString();
        ObjectNode submitBody = objectMapper.createObjectNode();
        submitBody.putObject("user").put("uid", "chatbot");
        ObjectNode audio = submitBody.putObject("audio");
        audio.put("format", ext.toLowerCase(Locale.ROOT));
        audio.put("url", audioUrl);
        ObjectNode req = submitBody.putObject("request");
        req.put("model_name", "bigmodel");
        req.put("enable_itn", true);
        req.put("enable_punc", true);

        ResponseEntity<String> submitResp = restClient.post()
                .uri(asrEndpoint + "/submit")
                .header("X-Api-Key", apiKey)
                .header("X-Api-Resource-Id", asrResourceId)
                .header("X-Api-Request-Id", taskId)
                .header("X-Api-Sequence", "-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(submitBody.toString())
                .retrieve()
                .toEntity(String.class);

        String submitCode = submitResp.getHeaders().getFirst("X-Api-Status-Code");
        String submitMsg = submitResp.getHeaders().getFirst("X-Api-Message");
        if (!ASR_OK.equals(submitCode)) {
            log.warn("ASR submit failed: code={} msg={}", submitCode, submitMsg);
            throw new VoiceException("识别提交失败: " + safeMessage(submitMsg));
        }

        // 4. 轮询结果（最长约 45 秒）
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            String code = queryAsr(taskId);
            if (ASR_OK.equals(code)) {
                return parseAsrResult(taskId);
            }
            if (ASR_SILENCE.equals(code)) {
                throw new VoiceException("没有听清，请再试一次");
            }
            // 处理中/排队中 → 等待后重试
            if (!ASR_PROCESSING.equals(code) && !ASR_QUEUED.equals(code)) {
                String msg = lastAsrMessage(taskId);
                log.warn("ASR query failed: code={} msg={}", code, msg);
                throw new VoiceException("识别失败: " + safeMessage(msg));
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new VoiceException("识别超时，请重试");
    }

    private String queryAsr(String taskId) {
        ResponseEntity<String> resp = restClient.post()
                .uri(asrEndpoint + "/query")
                .header("X-Api-Key", apiKey)
                .header("X-Api-Resource-Id", asrResourceId)
                .header("X-Api-Request-Id", taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .retrieve()
                .toEntity(String.class);
        return resp.getHeaders().getFirst("X-Api-Status-Code");
    }

    private String lastAsrMessage(String taskId) {
        try {
            ResponseEntity<String> resp = restClient.post()
                    .uri(asrEndpoint + "/query")
                    .header("X-Api-Key", apiKey)
                    .header("X-Api-Resource-Id", asrResourceId)
                    .header("X-Api-Request-Id", taskId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{}")
                    .retrieve()
                    .toEntity(String.class);
            return resp.getHeaders().getFirst("X-Api-Message");
        } catch (Exception e) {
            return null;
        }
    }

    private String parseAsrResult(String taskId) {
        ResponseEntity<String> resp = restClient.post()
                .uri(asrEndpoint + "/query")
                .header("X-Api-Key", apiKey)
                .header("X-Api-Resource-Id", asrResourceId)
                .header("X-Api-Request-Id", taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .retrieve()
                .toEntity(String.class);
        try {
            JsonNode root = objectMapper.readTree(resp.getBody() == null ? "{}" : resp.getBody());
            JsonNode result = root.path("result");
            String text = result.path("text").asText("").trim();
            if (text.isEmpty()) {
                throw new VoiceException("没有识别到有效内容，请再试一次");
            }
            return text;
        } catch (VoiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析 ASR 结果失败", e);
            throw new VoiceException("识别结果解析失败");
        }
    }

    /* ==================== TTS：语音合成 2.0 ==================== */

    /**
     * 文本合成 mp3 音频字节。
     */
    public byte[] synthesize(String text) {
        if (apiKey.isBlank()) {
            throw new VoiceException("语音服务未配置（缺少 CHATBOT_VOICE_API_KEY）");
        }
        String content = StringUtils.hasText(text) ? text.trim() : "";
        if (content.isEmpty()) {
            throw new VoiceException("合成文本为空");
        }
        if (content.length() > TTS_MAX_CHARS) {
            content = content.substring(0, TTS_MAX_CHARS);
        }

        // TTS 内存缓存：同一文本直接复用音频，减少往返延迟
        byte[] cached = ttsCache.get(content);
        if (cached != null) {
            return cached;
        }

        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode params = body.putObject("req_params");
        params.put("text", content);
        params.put("speaker", ttsSpeaker);
        ObjectNode audioParams = params.putObject("audio_params");
        audioParams.put("format", "mp3");
        audioParams.put("sample_rate", 24000);

        ResponseEntity<String> resp = restClient.post()
                .uri(ttsEndpoint)
                .header("X-Api-Key", apiKey)
                .header("X-Api-Resource-Id", ttsResourceId)
                .header("X-Api-Request-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .toEntity(String.class);

        // 响应为 HTTP Chunked 多行 JSON（NDJSON）：每行一个对象，
        // 音频行 {"code":0,"data":"<base64>"}，结束行 {"code":20000000,"message":"OK"}
        String raw = resp.getBody() == null ? "" : resp.getBody();
        if (raw.isBlank()) {
            throw new VoiceException("语音合成结果为空");
        }
        try {
            StringBuilder audioData = new StringBuilder();
            String[] lines = raw.split("\\r?\\n");
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                int code = node.path("code").asInt(-1);
                String msg = node.path("message").asText("");
                if (code != 0 && code != 20000000) {
                    log.warn("TTS failed: code={} msg={}", code, msg);
                    throw new VoiceException("语音合成失败: " + safeMessage(msg));
                }
                String data = node.path("data").asText("");
                if (!data.isEmpty()) {
                    audioData.append(data);
                }
            }
            if (audioData.length() == 0) {
                throw new VoiceException("语音合成结果为空");
            }
            byte[] audio = Base64.getDecoder().decode(audioData.toString());
            // 写入缓存（超上限时移除最早一条，简单 FIFO）
            ttsCache.put(content, audio);
            if (ttsCache.size() > TTS_CACHE_MAX) {
                String firstKey = ttsCache.keySet().iterator().next();
                ttsCache.remove(firstKey);
            }
            return audio;
        } catch (VoiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析 TTS 结果失败", e);
            throw new VoiceException("语音合成结果解析失败");
        }
    }

    /* ==================== 音频文件访问 ==================== */

    public Path resolveAudioFile(String name) {
        if (name == null || !name.matches("[0-9a-f]{32}\\.(mp3|wav|m4a|ogg)")) {
            throw new VoiceException("非法文件名");
        }
        Path p = audioDirPath().resolve(name).normalize();
        if (!p.startsWith(audioDirPath())) {
            throw new VoiceException("非法路径");
        }
        return p;
    }

    public String contentTypeOf(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return switch (n.substring(n.lastIndexOf('.') + 1)) {
            case "wav" -> "audio/wav";
            case "m4a" -> "audio/mp4";
            case "ogg" -> "audio/ogg";
            default -> "audio/mpeg";
        };
    }

    /* ==================== 内部工具 ==================== */

    private Path audioDirPath() {
        return Paths.get(audioDir).toAbsolutePath().normalize();
    }

    private String publicAudioUrl(HttpServletRequest request) {
        if (StringUtils.hasText(publicBaseUrl)) {
            return publicBaseUrl.replaceAll("/+$", "");
        }
        String host = request.getHeader("X-Forwarded-Host");
        if (!StringUtils.hasText(host)) {
            host = request.getHeader("Host");
        }
        if (!StringUtils.hasText(host)) {
            throw new VoiceException("无法确定公网地址，请配置 CHATBOT_VOICE_PUBLIC_BASE_URL");
        }
        // 去掉端口，统一走 https（隧道/代理场景）
        host = host.split(",")[0].trim();
        int colon = host.indexOf(':');
        if (colon > 0 && host.indexOf(']') < colon) {
            host = host.substring(0, colon);
        }
        return "https://" + host;
    }

    private String extensionOf(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "mp3";
        }
        return filename.substring(filename.lastIndexOf('.') + 1);
    }

    private String safeMessage(String msg) {
        return (msg == null || msg.isBlank()) ? "未知错误" : msg;
    }

    /** 语音相关业务异常（中文提示可直接展示给用户） */
    public static class VoiceException extends RuntimeException {
        public VoiceException(String message) {
            super(message);
        }
    }
}

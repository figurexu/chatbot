package com.chatbot.llm;

import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 chat/completions 客户端。
 * 适用于豆包方舟(Ark)、DeepSeek、OpenAI 等所有 OpenAI 兼容接口：
 * 配置 CHATBOT_LLM_BASE_URL / CHATBOT_LLM_API_KEY / CHATBOT_LLM_MODEL 即可。
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final int timeoutSeconds;

    public OpenAiCompatibleLlmClient(RestClient restClient,
                                     String baseUrl,
                                     String apiKey,
                                     String model,
                                     double temperature,
                                     int maxTokens,
                                     int timeoutSeconds) {
        this.restClient = restClient;
        this.baseUrl = trimSlash(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeoutSeconds = timeoutSeconds;
    }

    private static String trimSlash(String url) {
        return url == null ? "" : url.replaceAll("/+$", "");
    }

    @Override
    public String complete(String characterName, String systemPrompt, List<ChatMessage> history, String userMessage) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (ChatMessage m : history) {
            messages.add(Map.of("role", m.role(), "content", m.content()));
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);

        String url = baseUrl + "/chat/completions";
        try {
            Map<?, ?> resp = restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (resp == null) {
                throw new ChatLlmException("LLM 接口无响应");
            }
            List<?> choices = (List<?>) resp.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new ChatLlmException("LLM 返回无 choices: " + resp);
            }
            Map<?, ?> first = (Map<?, ?>) choices.get(0);
            Map<?, ?> message = (Map<?, ?>) first.get("message");
            Object content = message == null ? null : message.get("content");
            if (content == null || content.toString().isBlank()) {
                throw new ChatLlmException("LLM 返回内容为空");
            }
            return content.toString().trim();
        } catch (ChatLlmException e) {
            throw e;
        } catch (Exception e) {
            throw new ChatLlmException("调用 LLM 失败: " + e.getMessage(), e);
        }
    }
}

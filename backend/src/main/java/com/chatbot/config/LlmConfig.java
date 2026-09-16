package com.chatbot.config;

import com.chatbot.llm.LlmClient;
import com.chatbot.llm.MockLlmClient;
import com.chatbot.llm.OpenAiCompatibleLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public RestClient llmRestClient() {
        return RestClient.builder().build();
    }

    @Bean
    public LlmClient llmClient(@Value("${chatbot.llm.provider:mock}") String provider,
                               @Value("${chatbot.llm.base-url:}") String baseUrl,
                               @Value("${chatbot.llm.api-key:}") String apiKey,
                               @Value("${chatbot.llm.model:}") String model,
                               @Value("${chatbot.llm.temperature:0.8}") double temperature,
                               @Value("${chatbot.llm.max-tokens:1024}") int maxTokens,
                               @Value("${chatbot.llm.timeout-seconds:30}") int timeoutSeconds,
                               RestClient restClient) {
        String effective = provider.trim().toLowerCase();
        // 选择了 openai 但缺少 key/model 时自动降级为 mock，避免启动即失败
        if ("openai".equals(effective) && (apiKey.isBlank() || model.isBlank())) {
            log.warn("LLM provider=openai 但缺少 CHATBOT_LLM_API_KEY / CHATBOT_LLM_MODEL，自动降级为 mock 模式");
            effective = "mock";
        }
        return switch (effective) {
            case "openai" -> new OpenAiCompatibleLlmClient(restClient, baseUrl, apiKey, model, temperature, maxTokens, timeoutSeconds);
            case "mock" -> new MockLlmClient();
            default -> throw new IllegalArgumentException("不支持的 LLM provider: " + provider);
        };
    }
}

package com.chatbot.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @Value("${chatbot.app-version:1.0.0}")
    private String version;

    @Value("${chatbot.llm.provider:mock}")
    private String llmProvider;

    /** k8s 探针端点（与平台 blog 应用一致的约定） */
    @GetMapping(value = "/healthz", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> health() {
        return Map.of("status", "UP", "version", version, "llm", llmProvider);
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_PLAIN_VALUE)
    public String home() {
        return "History Character Chatbot API is running. version=" + version + ", llm=" + llmProvider;
    }
}

package com.zhulikang.aimatch.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Component
public class OpenAiCompatibleClient implements AiClient {
    private final RestTemplate restTemplate;
    private final String endpoint;
    private final String apiKey;
    private final String model;

    public OpenAiCompatibleClient(
        @Value("${ai.endpoint}") String endpoint,
        @Value("${ai.api-key}") String apiKey,
        @Value("${ai.model}") String model
    ) {
        this(new RestTemplate(), endpoint, apiKey, model);
    }

    OpenAiCompatibleClient(RestTemplate restTemplate, String endpoint, String apiKey, String model) {
        this.restTemplate = restTemplate;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String complete(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        Map<String, Object> body = Map.of(
            "model", model,
            "messages", List.of(Map.of("role", "user", "content", prompt)),
            "temperature", 0.2
        );

        Map<?, ?> response = restTemplate.postForObject(endpoint, new HttpEntity<>(body, headers), Map.class);
        if (response == null) {
            throw new IllegalStateException("AI response is empty");
        }
        List<?> choices = (List<?>) response.get("choices");
        Map<?, ?> first = (Map<?, ?>) choices.getFirst();
        Map<?, ?> message = (Map<?, ?>) first.get("message");
        return String.valueOf(message.get("content"));
    }
}

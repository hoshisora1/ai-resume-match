package com.zhulikang.aimatch.ai;

import com.zhulikang.aimatch.observability.AnalysisMetrics;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiCompatibleClient implements AiClient {
    private final RestTemplate restTemplate;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final AnalysisMetrics metrics;

    public OpenAiCompatibleClient(
        RestTemplateBuilder restTemplateBuilder,
        @Value("${ai.endpoint}") String endpoint,
        @Value("${ai.api-key}") String apiKey,
        @Value("${ai.model}") String model,
        AnalysisMetrics metrics
    ) {
        this(
            restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(30))
                .build(),
            endpoint,
            apiKey,
            model,
            metrics
        );
    }

    OpenAiCompatibleClient(
        RestTemplate restTemplate,
        String endpoint,
        String apiKey,
        String model,
        AnalysisMetrics metrics
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("AI API key must not be blank");
        }
        this.restTemplate = restTemplate;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.metrics = metrics;
    }

    @Override
    public String complete(String prompt) {
        Timer.Sample sample = metrics.startAiCall();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        Map<String, Object> body = Map.of(
            "model", model,
            "messages", List.of(Map.of("role", "user", "content", prompt)),
            "temperature", 0.2
        );

        try {
            Map<?, ?> response = restTemplate.postForObject(endpoint, new HttpEntity<>(body, headers), Map.class);
            if (response == null) {
                throw new IllegalStateException("AI response is empty");
            }
            List<?> choices = (List<?>) response.get("choices");
            Map<?, ?> first = (Map<?, ?>) choices.getFirst();
            Map<?, ?> message = (Map<?, ?>) first.get("message");
            metrics.aiCallFinished(sample, "success");
            return String.valueOf(message.get("content"));
        } catch (RuntimeException ex) {
            metrics.aiCallFinished(sample, "failure");
            throw ex;
        }
    }
}

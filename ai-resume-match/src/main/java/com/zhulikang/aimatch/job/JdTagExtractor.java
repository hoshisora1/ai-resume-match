package com.zhulikang.aimatch.job;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class JdTagExtractor {
    private static final List<String> KNOWN_TAGS = List.of(
        "Java", "Spring Boot", "Spring MVC", "MyBatis", "JPA",
        "MySQL", "Redis", "RabbitMQ", "Kafka", "Docker",
        "RAG", "向量检索", "大模型"
    );

    public List<String> extractTags(String jdText) {
        String lower = jdText == null ? "" : jdText.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String tag : KNOWN_TAGS) {
            if (lower.contains(tag.toLowerCase())) {
                result.add(tag);
            }
        }
        return result;
    }

    public String toStorageValue(List<String> tags) {
        return String.join(",", tags);
    }
}

package com.zhulikang.aimatch.job;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class JdTagExtractor {
    private static final List<String> KNOWN_TAGS = List.of(
        "Java", "Spring Boot", "Spring MVC", "MyBatis", "JPA",
        "MySQL", "Redis", "RabbitMQ", "Kafka", "Docker",
        "RAG", "向量检索", "大模型"
    );

    public List<String> extractTags(String jdText) {
        return KNOWN_TAGS.stream()
            .filter(tag -> Pattern.compile(
                (tag.charAt(0) < 128 ? "(?<![A-Za-z0-9_+#])" : "")
                    + Pattern.quote(tag)
                    + (tag.charAt(tag.length() - 1) < 128 ? "(?![A-Za-z0-9_+#])" : ""),
                Pattern.CASE_INSENSITIVE
            ).matcher(jdText).find())
            .toList();
    }

    public String toStorageValue(List<String> tags) {
        return String.join(",", tags);
    }
}

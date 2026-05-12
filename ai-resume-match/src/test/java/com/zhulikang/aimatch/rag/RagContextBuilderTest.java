package com.zhulikang.aimatch.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagContextBuilderTest {
    private final RagContextBuilder builder = new RagContextBuilder();

    @Test
    void buildsPromptWithRetrievedChunksJobAndTags() {
        String prompt = builder.build(
            List.of("召回片段：高性能秒杀系统，使用 Redis Kafka MySQL"),
            "要求：Java 后端，熟悉 Redis 和 Kafka",
            List.of("Java", "Redis", "Kafka")
        );

        assertThat(prompt).contains("你是资深 Java 后端面试官");
        assertThat(prompt).contains("召回片段");
        assertThat(prompt).contains("Redis,Kafka");
        assertThat(prompt).contains("匹配分数");
        assertThat(prompt).doesNotContain("完整简历全文");
    }
}

package com.zhulikang.aimatch.job;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdTagExtractorTest {
    private final JdTagExtractor extractor = new JdTagExtractor();

    @Test
    void extractsBackendSkillTagsInStableOrder() {
        String jd = "熟悉 Java、Spring Boot、MySQL、Redis，有 Kafka 项目经验优先";

        assertThat(extractor.extractTags(jd))
            .containsExactly("Java", "Spring Boot", "MySQL", "Redis", "Kafka");
    }

    @Test
    void doesNotInventJavaOrRagTagsFromUnrelatedWords() {
        assertThat(extractor.extractTags("Build JavaScript frontend and storage services."))
            .isEmpty();
        assertThat(extractor.extractTags("熟悉Java，使用Java-based服务和RAG检索。"))
            .containsExactly("Java", "RAG");
    }

    @Test
    void joinsTagsForStorage() {
        assertThat(extractor.toStorageValue(java.util.List.of("Java", "Redis")))
            .isEqualTo("Java,Redis");
    }
}

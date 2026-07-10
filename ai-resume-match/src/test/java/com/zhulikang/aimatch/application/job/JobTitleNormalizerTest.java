package com.zhulikang.aimatch.application.job;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobTitleNormalizerTest {
    private final JobTitleNormalizer normalizer = new JobTitleNormalizer();

    @Test
    void prefersTrimmedExplicitTitle() {
        assertThat(normalizer.normalize("  AI 应用开发工程师  ", "第一行 JD"))
            .isEqualTo("AI 应用开发工程师");
    }

    @Test
    void derivesTitleFromFirstNonBlankJdLine() {
        assertThat(normalizer.normalize(null, "\n  高级后端工程师  \n负责 Java 平台"))
            .isEqualTo("高级后端工程师");
    }

    @Test
    void truncatesDerivedTitleAndFallsBackWhenNeeded() {
        assertThat(normalizer.normalize(null, "x".repeat(121))).hasSize(120);
        assertThat(normalizer.normalize(" ", " \n ")).isEqualTo("未命名岗位");
    }
}

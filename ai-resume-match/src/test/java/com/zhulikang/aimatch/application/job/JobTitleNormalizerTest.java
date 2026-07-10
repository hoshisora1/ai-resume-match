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

    @Test
    void acceptsMaximumCodePointTitleEndingInEmoji() {
        String title = "x".repeat(119) + "\uD83D\uDE80";

        assertThat(normalizer.normalize(title, "ignored")).isEqualTo(title);
    }

    @Test
    void truncatesOverlongTitleWithoutSplittingSurrogatePair() {
        String expected = "x".repeat(119) + "\uD83D\uDE80";

        String normalized = normalizer.normalize(expected + "z", "ignored");

        assertThat(normalized).isEqualTo(expected);
        assertThat(normalized.codePointCount(0, normalized.length())).isEqualTo(120);
    }

    @Test
    void acceptsMaximumTitleMadeOfSupplementaryCodePoints() {
        String title = "\uD83D\uDE80".repeat(120);

        assertThat(normalizer.normalize(title, "ignored")).isEqualTo(title);
    }

    @Test
    void stripsUnicodeWhitespaceFromExplicitTitle() {
        assertThat(normalizer.normalize("\u3000Backend Engineer\u3000", "ignored"))
            .isEqualTo("Backend Engineer");
    }

    @Test
    void stripsUnicodeWhitespaceFromDerivedTitle() {
        assertThat(normalizer.normalize(null, "\u3000\n\u3000高级后端工程师\u3000\n负责 Java 平台"))
            .isEqualTo("高级后端工程师");
    }
}

package com.zhulikang.aimatch.application.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisInputFingerprintTest {
    private static final String EXPECTED =
        "6d4c96fb440708f7e456e0598d60ea1b940fd2441362c792961f8bd89345b5d1";

    @Test
    void matchesTheCrossLanguageLengthPrefixedVector() {
        assertThat(AnalysisInputFingerprint.create(input(12L, "correlation-12"))).isEqualTo(EXPECTED);
    }

    @Test
    void excludesOperationalCorrelationIdButScopesFingerprintToTask() {
        assertThat(AnalysisInputFingerprint.create(input(12L, "another-correlation")))
            .isEqualTo(EXPECTED);
        assertThat(AnalysisInputFingerprint.create(input(13L, "correlation-12")))
            .isNotEqualTo(EXPECTED);
    }

    @Test
    void changesWhenNormalizedAnalysisContentChanges() {
        AnalysisInput changed = new AnalysisInput(
            12L,
            "Java Redis RabbitMQ changed",
            "Agent Engineer",
            "Need Java and tool calling",
            List.of("Java", "Agent"),
            "correlation-12"
        );

        assertThat(AnalysisInputFingerprint.create(changed)).isNotEqualTo(EXPECTED);
    }

    private AnalysisInput input(long taskId, String correlationId) {
        return new AnalysisInput(
            taskId,
            "Java Redis RabbitMQ",
            "Agent Engineer",
            "Need Java and tool calling",
            List.of("Java", "Agent"),
            correlationId
        );
    }
}

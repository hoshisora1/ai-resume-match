package com.zhulikang.aimatch.application.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisSubmissionIdempotencyKeyTest {
    @Test
    void hashesSafeKeyWithoutPersistingItsPlaintext() {
        String hash = AnalysisSubmissionIdempotencyKey.hash("client:request-123_v1.0");

        assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(hash).doesNotContain("request-123");
        assertThat(AnalysisSubmissionIdempotencyKey.hash("Client-Key"))
            .isNotEqualTo(AnalysisSubmissionIdempotencyKey.hash("client-key"));
    }

    @Test
    void rejectsUnsafeKey() {
        assertThatThrownBy(() -> AnalysisSubmissionIdempotencyKey.hash("contains space"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnalysisSubmissionIdempotencyKey.hash("a".repeat(129)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}

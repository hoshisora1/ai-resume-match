package com.zhulikang.aimatch.application.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisSubmissionRequestFingerprintTest {
    @Test
    void fingerprintsEveryRequestInputWithSha256() {
        MockMultipartFile firstFile = new MockMultipartFile(
            "file", "resume.pdf", "application/pdf", new byte[] {1, 2, 3}
        );
        MockMultipartFile changedFile = new MockMultipartFile(
            "file", "resume.pdf", "application/pdf", new byte[] {1, 2, 4}
        );

        String fingerprint = AnalysisSubmissionRequestFingerprint.create(
            firstFile, "Backend Engineer", "Java Redis"
        );

        assertThat(fingerprint).matches("[0-9a-f]{64}");
        assertThat(AnalysisSubmissionRequestFingerprint.create(
            firstFile, "Backend Engineer", "Java Redis"
        )).isEqualTo(fingerprint);
        assertThat(AnalysisSubmissionRequestFingerprint.create(
            changedFile, "Backend Engineer", "Java Redis"
        )).isNotEqualTo(fingerprint);
        assertThat(AnalysisSubmissionRequestFingerprint.create(
            firstFile, "Backend Engineer", "Java Kafka"
        )).isNotEqualTo(fingerprint);
    }
}

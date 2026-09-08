package com.zhulikang.aimatch.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhulikang.aimatch.analysis.MatchReportView;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchReportResponseTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keepsLegacyMarkdownReportsNullable() {
        MatchReportResponse response = MatchReportResponse.from(
            new MatchReportView(1L, 50, "legacy", LocalDateTime.of(2026, 8, 18, 1, 0)),
            objectMapper
        );

        assertThat(response.structuredReport()).isNull();
        assertThat(response.provenance()).isNull();
    }

    @Test
    void rejectsStoredStructuredPayloadMissingRequiredContractFields() {
        MatchReportView invalid = new MatchReportView(
            1L,
            50,
            "invalid",
            "match-report-v2",
            "{\"schemaVersion\":\"match-report-v2\"}",
            null,
            LocalDateTime.of(2026, 8, 18, 1, 0)
        );

        assertThatThrownBy(() -> MatchReportResponse.from(invalid, objectMapper))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("structured report");
    }

    @Test
    void rejectsStoredPayloadThatIsNotAnObject() {
        MatchReportView invalid = new MatchReportView(
            1L,
            50,
            "invalid",
            "match-report-v2",
            "[]",
            null,
            LocalDateTime.of(2026, 8, 18, 1, 0)
        );

        assertThatThrownBy(() -> MatchReportResponse.from(invalid, objectMapper))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("was not a JSON object");
    }
}

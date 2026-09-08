package com.zhulikang.aimatch.application.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhulikang.aimatch.application.analysis.AgentProtocol.AgentAnalysisResponse;
import com.zhulikang.aimatch.application.analysis.AgentProtocol.AnalysisProvenance;
import com.zhulikang.aimatch.application.analysis.AgentProtocol.StructuredReport;

/** Converts validated wire responses into persisted reports and provenance. */
final class AgentReportMapper {
    private final ObjectMapper objectMapper;

    AgentReportMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    AnalysisResult map(AgentAnalysisResponse body, AnalysisInput input) {
        if (body == null || !input.taskId().equals(body.taskId())) {
            throw new IllegalArgumentException("Agent service returned a mismatched task response");
        }
        if (body.matchScore() == null) {
            throw new IllegalArgumentException("Agent service response did not contain a match score");
        }
        StructuredReport report = body.structuredReport();
        if (report == null) {
            return new AnalysisResult(body.matchScore(), body.reportMarkdown());
        }
        AgentReportValidator.validate(body, report, input);
        AnalysisProvenance provenance = new AnalysisProvenance(
            "analysis-run-v1",
            input.correlationId(),
            body.traceId(),
            body.model(),
            body.promptVersion(),
            body.retrieverVersion(),
            body.verifierVersion(),
            body.steps(),
            body.runMetadata(),
            body.modelUsage(),
            body.toolTrace()
        );
        return new AnalysisResult(
            body.matchScore(),
            body.reportMarkdown(),
            report.schemaVersion(),
            writeJson(report, "structured report"),
            writeJson(provenance, "analysis provenance")
        );
    }

    private String writeJson(Object value, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Could not serialize " + label, ex);
        }
    }

}

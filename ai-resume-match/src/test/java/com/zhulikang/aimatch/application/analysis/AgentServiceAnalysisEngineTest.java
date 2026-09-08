package com.zhulikang.aimatch.application.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AgentServiceAnalysisEngineTest {
    @Test
    void sendsBoundedAgentRequestAndReturnsStructuredReport() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentServiceAnalysisEngine engine = new AgentServiceAnalysisEngine(
            restTemplate,
            "http://agent.test:8000/",
            "agent-secret",
            new AnalysisMetrics(registry),
            new ObjectMapper()
        );
        server.expect(requestTo("http://agent.test:8000/v1/agent/analyze"))
            .andExpect(header(AgentServiceAnalysisEngine.TOKEN_HEADER, "agent-secret"))
            .andExpect(header("X-Correlation-Id", "correlation-12"))
            .andExpect(jsonPath("$.taskId").value(12))
            .andExpect(jsonPath("$.skillTags[0]").value("Java"))
            .andRespond(withSuccess(successfulResponse("resume:0"), MediaType.APPLICATION_JSON));

        AnalysisResult result = engine.analyze(input());

        assertThat(result.matchScore()).isEqualTo(100);
        assertThat(result.reportContent()).contains("Grounded report");
        assertThat(result.reportSchemaVersion()).isEqualTo("match-report-v2");
        assertThat(result.structuredReportJson()).contains("\"requirementId\":\"requirement:0\"");
        assertThat(result.provenanceJson())
            .contains("\"schemaVersion\":\"analysis-run-v1\"")
            .contains("\"retrieverVersion\":\"hashing-test-v1\"")
            .contains("\"traceId\":\"0123456789abcdef0123456789abcdef\"")
            .contains("\"agentRuntimeVersion\":\"bounded-tool-agent-v1\"")
            .contains("\"inputFingerprint\":\"6d4c96fb440708f7e456e0598d60ea1b940fd2441362c792961f8bd89345b5d1\"")
            .contains("\"estimatedCostUsd\":\"0.00027000\"")
            .contains("\"pricingVersion\":\"test-price-2026-08-01\"")
            .doesNotContain("Java Redis RabbitMQ");
        assertThat(registry.counter("agent.calls", "outcome", "success").count()).isEqualTo(1.0);
        server.verify();
    }

    @Test
    void treatsClientRejectionAsFinalInvalidRequest() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AgentServiceAnalysisEngine engine = new AgentServiceAnalysisEngine(
            restTemplate,
            "http://agent.test:8000",
            "agent-secret",
            new AnalysisMetrics(new SimpleMeterRegistry()),
            new ObjectMapper()
        );
        server.expect(requestTo("http://agent.test:8000/v1/agent/analyze"))
            .andRespond(withStatus(UNAUTHORIZED));

        assertThatThrownBy(() -> engine.analyze(input()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("status 401");
    }

    @Test
    void rejectsSuccessfulResponseWithoutScore() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AgentServiceAnalysisEngine engine = new AgentServiceAnalysisEngine(
            restTemplate,
            "http://agent.test:8000",
            "agent-secret",
            new AnalysisMetrics(new SimpleMeterRegistry()),
            new ObjectMapper()
        );
        server.expect(requestTo("http://agent.test:8000/v1/agent/analyze"))
            .andRespond(withSuccess("""
                {
                  "taskId": 12,
                  "reportMarkdown": "report",
                  "steps": 3,
                  "model": "test-model",
                  "toolTrace": []
                }
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> engine.analyze(input()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("match score");
    }

    @Test
    void rejectsStructuredReportWithUnknownEvidenceReference() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AgentServiceAnalysisEngine engine = new AgentServiceAnalysisEngine(
            restTemplate,
            "http://agent.test:8000",
            "agent-secret",
            new AnalysisMetrics(new SimpleMeterRegistry()),
            new ObjectMapper()
        );
        server.expect(requestTo("http://agent.test:8000/v1/agent/analyze"))
            .andRespond(withSuccess(successfulResponse("resume:999"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> engine.analyze(input()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("evidence graph");
    }

    @Test
    void rejectsRequirementEvidenceThatWasNotAcceptedByVerifier() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AgentServiceAnalysisEngine engine = new AgentServiceAnalysisEngine(
            restTemplate,
            "http://agent.test:8000",
            "agent-secret",
            new AnalysisMetrics(new SimpleMeterRegistry()),
            new ObjectMapper()
        );
        server.expect(requestTo("http://agent.test:8000/v1/agent/analyze"))
            .andRespond(withSuccess(
                successfulResponse("resume:0", "resume:1"),
                MediaType.APPLICATION_JSON
            ));

        assertThatThrownBy(() -> engine.analyze(input()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unverified evidence");
    }

    @Test
    void rejectsPositiveClaimWithoutEvidence() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AgentServiceAnalysisEngine engine = new AgentServiceAnalysisEngine(
            restTemplate,
            "http://agent.test:8000",
            "agent-secret",
            new AnalysisMetrics(new SimpleMeterRegistry()),
            new ObjectMapper()
        );
        String response = successfulResponse("resume:0").replace(
            "{\"claim\":\"Java supported\",\"evidenceIds\":[\"resume:0\"]}",
            "{\"claim\":\"Java supported\",\"evidenceIds\":[]}"
        );
        server.expect(requestTo("http://agent.test:8000/v1/agent/analyze"))
            .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> engine.analyze(input()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid claim");
    }

    @Test
    void rejectsCostEstimateWithoutItsPricingVersion() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AgentServiceAnalysisEngine engine = new AgentServiceAnalysisEngine(
            restTemplate,
            "http://agent.test:8000",
            "agent-secret",
            new AnalysisMetrics(new SimpleMeterRegistry()),
            new ObjectMapper()
        );
        String response = successfulResponse("resume:0").replace(
            "\"pricingVersion\": \"test-price-2026-08-01\"",
            "\"pricingVersion\": null"
        );
        server.expect(requestTo("http://agent.test:8000/v1/agent/analyze"))
            .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> engine.analyze(input()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cost provenance");
    }

    @Test
    void acceptsRollingResponseWithoutOptionalCostFields() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AgentServiceAnalysisEngine engine = new AgentServiceAnalysisEngine(
            restTemplate,
            "http://agent.test:8000",
            "agent-secret",
            new AnalysisMetrics(new SimpleMeterRegistry()),
            new ObjectMapper()
        );
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode responseBody = (ObjectNode) objectMapper.readTree(successfulResponse("resume:0"));
        ObjectNode modelUsage = (ObjectNode) responseBody.get("modelUsage");
        modelUsage.remove("estimatedCostUsd");
        modelUsage.remove("pricingVersion");
        String response = objectMapper.writeValueAsString(responseBody);
        server.expect(requestTo("http://agent.test:8000/v1/agent/analyze"))
            .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        AnalysisResult result = engine.analyze(input());

        assertThat(result.provenanceJson())
            .contains("\"estimatedCostUsd\":null")
            .contains("\"pricingVersion\":null");
    }

    @Test
    void acceptsRollingResponseWithoutOptionalTraceId() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AgentServiceAnalysisEngine engine = new AgentServiceAnalysisEngine(
            restTemplate,
            "http://agent.test:8000",
            "agent-secret",
            new AnalysisMetrics(new SimpleMeterRegistry()),
            new ObjectMapper()
        );
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode responseBody = (ObjectNode) objectMapper.readTree(successfulResponse("resume:0"));
        responseBody.remove("traceId");
        server.expect(requestTo("http://agent.test:8000/v1/agent/analyze"))
            .andRespond(withSuccess(objectMapper.writeValueAsString(responseBody), MediaType.APPLICATION_JSON));

        AnalysisResult result = engine.analyze(input());

        assertThat(result.provenanceJson()).contains("\"traceId\":null");
    }

    @Test
    void acceptsRollingResponseWithoutOptionalRunMetadata() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AgentServiceAnalysisEngine engine = new AgentServiceAnalysisEngine(
            restTemplate,
            "http://agent.test:8000",
            "agent-secret",
            new AnalysisMetrics(new SimpleMeterRegistry()),
            new ObjectMapper()
        );
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode responseBody = (ObjectNode) objectMapper.readTree(successfulResponse("resume:0"));
        responseBody.remove("runMetadata");
        server.expect(requestTo("http://agent.test:8000/v1/agent/analyze"))
            .andRespond(withSuccess(objectMapper.writeValueAsString(responseBody), MediaType.APPLICATION_JSON));

        AnalysisResult result = engine.analyze(input());

        assertThat(result.provenanceJson()).contains("\"runMetadata\":null");
    }

    @Test
    void rejectsRunMetadataForDifferentInput() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AgentServiceAnalysisEngine engine = new AgentServiceAnalysisEngine(
            restTemplate,
            "http://agent.test:8000",
            "agent-secret",
            new AnalysisMetrics(new SimpleMeterRegistry()),
            new ObjectMapper()
        );
        String response = successfulResponse("resume:0").replace(
            "6d4c96fb440708f7e456e0598d60ea1b940fd2441362c792961f8bd89345b5d1",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );
        server.expect(requestTo("http://agent.test:8000/v1/agent/analyze"))
            .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> engine.analyze(input()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("run metadata");
    }

    @Test
    void rejectsRunMetadataThatDisagreesWithToolTrace() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AgentServiceAnalysisEngine engine = new AgentServiceAnalysisEngine(
            restTemplate,
            "http://agent.test:8000",
            "agent-secret",
            new AnalysisMetrics(new SimpleMeterRegistry()),
            new ObjectMapper()
        );
        String response = successfulResponse("resume:0").replace(
            "\"toolDurationMs\": 4",
            "\"toolDurationMs\": 5"
        );
        server.expect(requestTo("http://agent.test:8000/v1/agent/analyze"))
            .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> engine.analyze(input()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inconsistent run metadata");
    }

    @Test
    void rejectsMalformedTraceId() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AgentServiceAnalysisEngine engine = new AgentServiceAnalysisEngine(
            restTemplate,
            "http://agent.test:8000",
            "agent-secret",
            new AnalysisMetrics(new SimpleMeterRegistry()),
            new ObjectMapper()
        );
        String response = successfulResponse("resume:0").replace(
            "0123456789abcdef0123456789abcdef",
            "not-a-trace-id"
        );
        server.expect(requestTo("http://agent.test:8000/v1/agent/analyze"))
            .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> engine.analyze(input()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("trace ID");
    }

    @Test
    void leavesServerFailureRetryable() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AgentServiceAnalysisEngine engine = new AgentServiceAnalysisEngine(
            restTemplate,
            "http://agent.test:8000",
            "agent-secret",
            new AnalysisMetrics(new SimpleMeterRegistry()),
            new ObjectMapper()
        );
        server.expect(requestTo("http://agent.test:8000/v1/agent/analyze"))
            .andRespond(withServerError());

        assertThatThrownBy(() -> engine.analyze(input()))
            .isInstanceOf(RuntimeException.class)
            .isNotInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leavesRateLimitResponseRetryable() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentServiceAnalysisEngine engine = new AgentServiceAnalysisEngine(
            restTemplate,
            "http://agent.test:8000",
            "agent-secret",
            new AnalysisMetrics(registry),
            new ObjectMapper()
        );
        server.expect(requestTo("http://agent.test:8000/v1/agent/analyze"))
            .andRespond(withStatus(TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> engine.analyze(input()))
            .isInstanceOf(RuntimeException.class)
            .isNotInstanceOf(IllegalArgumentException.class);
        assertThat(registry.counter("agent.calls", "outcome", "retryable_rejection").count())
            .isEqualTo(1.0);
    }

    @Test
    void honorsExplicitFinalAgentErrorContract() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AgentServiceAnalysisEngine engine = new AgentServiceAnalysisEngine(
            restTemplate,
            "http://agent.test:8000",
            "agent-secret",
            new AnalysisMetrics(new SimpleMeterRegistry()),
            new ObjectMapper()
        );
        server.expect(requestTo("http://agent.test:8000/v1/agent/analyze"))
            .andRespond(withStatus(SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {
                      "code": "AGENT_PROTOCOL_ERROR",
                      "message": "do not retry or expose this detail",
                      "retryable": false,
                      "retryAfterSeconds": null
                    }
                    """));

        assertThatThrownBy(() -> engine.analyze(input()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AGENT_PROTOCOL_ERROR")
            .hasMessageContaining("status 503")
            .hasMessageNotContaining("do not retry");
    }

    @Test
    void honorsExplicitRetryableAgentErrorContract() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AgentServiceAnalysisEngine engine = new AgentServiceAnalysisEngine(
            restTemplate,
            "http://agent.test:8000",
            "agent-secret",
            new AnalysisMetrics(new SimpleMeterRegistry()),
            new ObjectMapper()
        );
        server.expect(requestTo("http://agent.test:8000/v1/agent/analyze"))
            .andRespond(withStatus(UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {
                      "code": "MODEL_PROVIDER_UNAVAILABLE",
                      "message": "retry later",
                      "retryable": true,
                      "retryAfterSeconds": 17
                    }
                    """));

        assertThatThrownBy(() -> engine.analyze(input()))
            .isInstanceOfSatisfying(
                AnalysisEngineUnavailableException.class,
                exception -> assertThat(exception.retryAfterSeconds()).isEqualTo(17)
            );
    }

    private AnalysisInput input() {
        return new AnalysisInput(
            12L,
            "Java Redis RabbitMQ",
            "Agent Engineer",
            "Need Java and tool calling",
            List.of("Java", "Agent"),
            "correlation-12"
        );
    }

    private String successfulResponse(String requirementEvidenceId) {
        return successfulResponse(requirementEvidenceId, requirementEvidenceId);
    }

    private String successfulResponse(String requirementEvidenceId, String verificationEvidenceId) {
        return """
            {
              "taskId": 12,
              "matchScore": 100,
              "reportMarkdown": "匹配分数: 100\\n\\n## 核心结论\\nGrounded report",
              "steps": 3,
              "model": "test-model",
              "promptVersion": "prompt-test-v1",
              "retrieverVersion": "hashing-test-v1",
              "verifierVersion": "verifier-test-v1",
              "traceId": "0123456789abcdef0123456789abcdef",
              "runMetadata": {
                "schemaVersion": "agent-run-v1",
                "requestSchemaVersion": "agent-analysis-request-v1",
                "agentRuntimeVersion": "bounded-tool-agent-v1",
                "inputFingerprintVersion": "sha256-task-scoped-length-prefixed-v1",
                "inputFingerprint": "6d4c96fb440708f7e456e0598d60ea1b940fd2441362c792961f8bd89345b5d1",
                "chatProviderCalls": 3,
                "chatProviderDurationMs": 80,
                "toolDurationMs": 4,
                "totalDurationMs": 125,
                "contextCharsSent": 2048
              },
              "modelUsage": {
                "promptTokens": 100,
                "completionTokens": 30,
                "totalTokens": 130,
                "providerReported": true,
                "estimatedCostUsd": "0.00027000",
                "pricingVersion": "test-price-2026-08-01"
              },
              "toolTrace": [
                {"name":"get_job_requirements","outcome":"success","durationMs":1},
                {"name":"search_resume_evidence","outcome":"success","durationMs":2},
                {"name":"submit_match_report","outcome":"success","durationMs":1}
              ],
              "structuredReport": {
                "schemaVersion": "match-report-v2",
                "matchScore": 100,
                "requirements": [{
                  "requirementId": "requirement:0",
                  "text": "Java",
                  "mustHave": true,
                  "weight": 2,
                  "modelStatus": "supported",
                  "status": "supported",
                  "explanation": "supported",
                  "evidenceIds": ["%s"],
                  "verification": {
                    "verifierVersion": "verifier-test-v1",
                    "status": "supported",
                    "termCoverage": 1.0,
                    "reason": "required_terms_supported",
                    "evidenceIds": ["%s"]
                  }
                }],
                "coreClaims": [{"claim":"Java supported","evidenceIds":["resume:0"]}],
                "matchedSkills": [],
                "skillGaps": ["none"],
                "recommendations": ["one", "two", "three"],
                "interviewQuestions": ["one?", "two?", "three?"],
                "evidence": [{
                  "evidenceId": "resume:0",
                  "excerpt": "Built Java services.",
                  "score": 0.9,
                  "sourceStart": 0,
                  "sourceEnd": 20
                }],
                "scoreBreakdown": {
                  "rawScore": 100,
                  "finalScore": 100,
                  "totalWeight": 2,
                  "supportedWeight": 2,
                  "partialWeight": 0,
                  "missingWeight": 0,
                  "mustHaveCapApplied": false
                }
              }
            }
            """.formatted(requirementEvidenceId, verificationEvidenceId);
    }
}

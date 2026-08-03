package com.zhulikang.aimatch.application.analysis;

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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

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
            new AnalysisMetrics(registry)
        );
        server.expect(requestTo("http://agent.test:8000/v1/agent/analyze"))
            .andExpect(header(AgentServiceAnalysisEngine.TOKEN_HEADER, "agent-secret"))
            .andExpect(header("X-Correlation-Id", "correlation-12"))
            .andExpect(jsonPath("$.taskId").value(12))
            .andExpect(jsonPath("$.skillTags[0]").value("Java"))
            .andRespond(withSuccess("""
                {
                  "taskId": 12,
                  "matchScore": 86,
                  "reportMarkdown": "匹配分数: 86\\n\\n## 核心结论\\nGrounded report",
                  "steps": 3,
                  "model": "test-model",
                  "modelUsage": {"promptTokens": 100, "completionTokens": 30, "totalTokens": 130},
                  "toolTrace": [
                    {"name":"get_job_requirements","outcome":"success","durationMs":1},
                    {"name":"search_resume_evidence","outcome":"success","durationMs":2},
                    {"name":"submit_match_report","outcome":"success","durationMs":1}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        AnalysisResult result = engine.analyze(input());

        assertThat(result.matchScore()).isEqualTo(86);
        assertThat(result.reportContent()).contains("Grounded report");
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
            new AnalysisMetrics(new SimpleMeterRegistry())
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
            new AnalysisMetrics(new SimpleMeterRegistry())
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
    void leavesServerFailureRetryable() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AgentServiceAnalysisEngine engine = new AgentServiceAnalysisEngine(
            restTemplate,
            "http://agent.test:8000",
            "agent-secret",
            new AnalysisMetrics(new SimpleMeterRegistry())
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
            new AnalysisMetrics(registry)
        );
        server.expect(requestTo("http://agent.test:8000/v1/agent/analyze"))
            .andRespond(withStatus(TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> engine.analyze(input()))
            .isInstanceOf(RuntimeException.class)
            .isNotInstanceOf(IllegalArgumentException.class);
        assertThat(registry.counter("agent.calls", "outcome", "retryable_rejection").count())
            .isEqualTo(1.0);
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
}

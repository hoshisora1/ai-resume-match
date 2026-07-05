package com.zhulikang.aimatch.ai;

import com.zhulikang.aimatch.observability.AnalysisMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiCompatibleClientTest {
    @Test
    void sendsOpenAiCompatibleRequestAndReturnsMessageContent() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
            restTemplate,
            "https://example.test/v1/chat/completions",
            "test-key",
            "test-model",
            new AnalysisMetrics(meterRegistry)
        );
        server.expect(requestTo("https://example.test/v1/chat/completions"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
            .andRespond(withSuccess("""
                {"choices":[{"message":{"content":"匹配分数：88"}}]}
                """, MediaType.APPLICATION_JSON));

        String result = client.complete("请分析");

        assertThat(result).isEqualTo("匹配分数：88");
        assertThat(meterRegistry.counter("ai.calls", "outcome", "success").count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("ai.call.duration").tag("outcome", "success").timer()).isNotNull();
        server.verify();
    }

    @Test
    void recordsFailureMetricWhenAiCallFails() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
            restTemplate,
            "https://example.test/v1/chat/completions",
            "test-key",
            "test-model",
            new AnalysisMetrics(meterRegistry)
        );
        server.expect(requestTo("https://example.test/v1/chat/completions"))
            .andRespond(withServerError());

        assertThatThrownBy(() -> client.complete("test prompt"))
            .isInstanceOf(RuntimeException.class);
        assertThat(meterRegistry.counter("ai.calls", "outcome", "failure").count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("ai.call.duration").tag("outcome", "failure").timer()).isNotNull();
        server.verify();
    }

    @Test
    void rejectsBlankApiKey() {
        assertThatThrownBy(() -> new OpenAiCompatibleClient(
            new RestTemplate(),
            "https://example.test/v1/chat/completions",
            " ",
            "test-model",
            new AnalysisMetrics(new SimpleMeterRegistry())
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AI API key");
    }
}

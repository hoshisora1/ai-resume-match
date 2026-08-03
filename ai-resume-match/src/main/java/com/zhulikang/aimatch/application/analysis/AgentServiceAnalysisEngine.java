package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.observability.AnalysisMetrics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

@Component
@ConditionalOnProperty(name = "analysis.engine", havingValue = "agent")
public class AgentServiceAnalysisEngine implements AnalysisEngine {
    static final String TOKEN_HEADER = "X-Agent-Token";
    private static final Logger log = LoggerFactory.getLogger(AgentServiceAnalysisEngine.class);

    private final RestTemplate restTemplate;
    private final String endpoint;
    private final String serviceToken;
    private final AnalysisMetrics metrics;

    @Autowired
    public AgentServiceAnalysisEngine(
        RestTemplateBuilder builder,
        @Value("${agent.base-url}") String baseUrl,
        @Value("${agent.token}") String serviceToken,
        @Value("${agent.connect-timeout:3s}") Duration connectTimeout,
        @Value("${agent.read-timeout:60s}") Duration readTimeout,
        AnalysisMetrics metrics
    ) {
        this(
            builder.setConnectTimeout(connectTimeout).setReadTimeout(readTimeout).build(),
            baseUrl,
            serviceToken,
            metrics
        );
    }

    AgentServiceAnalysisEngine(
        RestTemplate restTemplate,
        String baseUrl,
        String serviceToken,
        AnalysisMetrics metrics
    ) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Agent service base URL must not be blank");
        }
        if (serviceToken == null || serviceToken.isBlank()) {
            throw new IllegalArgumentException("Agent service token must not be blank");
        }
        this.restTemplate = restTemplate;
        this.endpoint = baseUrl.replaceAll("/+$", "") + "/v1/agent/analyze";
        this.serviceToken = serviceToken;
        this.metrics = metrics;
    }

    @Override
    public AnalysisResult analyze(AnalysisInput input) {
        Timer.Sample sample = metrics.startAgentCall();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(TOKEN_HEADER, serviceToken);
        headers.set("X-Correlation-Id", input.correlationId());
        AgentAnalysisRequest request = new AgentAnalysisRequest(
            input.taskId(),
            input.resumeText(),
            input.jobTitle(),
            input.jobDescription(),
            input.skillTags(),
            input.correlationId()
        );

        try {
            ResponseEntity<AgentAnalysisResponse> response = restTemplate.postForEntity(
                endpoint,
                new HttpEntity<>(request, headers),
                AgentAnalysisResponse.class
            );
            AgentAnalysisResponse body = response.getBody();
            if (body == null || body.taskId() == null || !input.taskId().equals(body.taskId())) {
                throw new IllegalArgumentException("Agent service returned a mismatched task response");
            }
            if (body.matchScore() == null) {
                throw new IllegalArgumentException("Agent service response did not contain a match score");
            }
            AnalysisResult result = new AnalysisResult(body.matchScore(), body.reportMarkdown());
            metrics.agentCallFinished(sample, "success");
            log.info(
                "event=agent_service_succeeded taskId={} steps={} toolCalls={} model={}",
                input.taskId(),
                body.steps(),
                body.toolTrace() == null ? 0 : body.toolTrace().size(),
                body.model()
            );
            return result;
        } catch (HttpClientErrorException ex) {
            int status = ex.getStatusCode().value();
            if (status == 408 || status == 429) {
                metrics.agentCallFinished(sample, "retryable_rejection");
                throw ex;
            }
            metrics.agentCallFinished(sample, "rejected");
            throw new IllegalArgumentException(
                "Agent service rejected the analysis request with status " + status
            );
        } catch (IllegalArgumentException ex) {
            metrics.agentCallFinished(sample, "invalid_response");
            throw ex;
        } catch (RuntimeException ex) {
            metrics.agentCallFinished(sample, "failure");
            throw ex;
        }
    }

    record AgentAnalysisRequest(
        Long taskId,
        String resumeText,
        String jobTitle,
        String jobDescription,
        List<String> skillTags,
        String correlationId
    ) {
    }

    record AgentAnalysisResponse(
        Long taskId,
        Integer matchScore,
        String reportMarkdown,
        int steps,
        String model,
        List<ToolTrace> toolTrace
    ) {
    }

    record ToolTrace(String name, String outcome, long durationMs) {
    }
}

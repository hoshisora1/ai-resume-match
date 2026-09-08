package com.zhulikang.aimatch.application.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhulikang.aimatch.application.analysis.AgentProtocol.AgentAnalysisRequest;
import com.zhulikang.aimatch.application.analysis.AgentProtocol.AgentAnalysisResponse;
import com.zhulikang.aimatch.application.analysis.AgentProtocol.AgentErrorResponse;
import com.zhulikang.aimatch.config.AgentProperties;
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Component
@ConditionalOnProperty(name = "analysis.engine", havingValue = "agent", matchIfMissing = true)
public class AgentServiceAnalysisEngine implements AnalysisEngine {
    static final String TOKEN_HEADER = "X-Agent-Token";
    private static final Logger log = LoggerFactory.getLogger(AgentServiceAnalysisEngine.class);

    private final RestTemplate restTemplate;
    private final String endpoint;
    private final String serviceToken;
    private final AnalysisMetrics metrics;
    private final ObjectMapper objectMapper;
    private final AgentReportMapper reportMapper;

    @Autowired
    public AgentServiceAnalysisEngine(
        RestTemplateBuilder builder,
        AgentProperties properties,
        AnalysisMetrics metrics,
        ObjectMapper objectMapper
    ) {
        this(
            builder
                .setConnectTimeout(properties.connectTimeout())
                .setReadTimeout(properties.readTimeout())
                .build(),
            properties.baseUrl(),
            properties.token(),
            metrics,
            objectMapper
        );
    }

    public AgentServiceAnalysisEngine(
        RestTemplateBuilder builder,
        String baseUrl,
        String serviceToken,
        Duration connectTimeout,
        Duration readTimeout,
        AnalysisMetrics metrics,
        ObjectMapper objectMapper
    ) {
        this(
            builder.setConnectTimeout(connectTimeout).setReadTimeout(readTimeout).build(),
            baseUrl,
            serviceToken,
            metrics,
            objectMapper
        );
    }

    AgentServiceAnalysisEngine(
        RestTemplate restTemplate,
        String baseUrl,
        String serviceToken,
        AnalysisMetrics metrics,
        ObjectMapper objectMapper
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
        this.objectMapper = objectMapper;
        this.reportMapper = new AgentReportMapper(objectMapper);
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
            AnalysisResult result = reportMapper.map(body, input);
            metrics.agentCallFinished(sample, "success");
            log.info(
                "event=agent_service_succeeded taskId={} steps={} toolCalls={} model={}",
                input.taskId(),
                body.steps(),
                body.toolTrace() == null ? 0 : body.toolTrace().size(),
                body.model()
            );
            return result;
        } catch (HttpStatusCodeException ex) {
            int status = ex.getStatusCode().value();
            AgentErrorResponse error = readError(ex.getResponseBodyAsString());
            boolean retryable = error != null && error.retryable() != null
                ? error.retryable()
                : status == 408 || status == 429 || status >= 500;
            String code = error == null || error.code() == null || error.code().isBlank()
                ? "HTTP_" + status
                : error.code();
            if (retryable) {
                metrics.agentCallFinished(sample, "retryable_rejection");
                throw new AnalysisEngineUnavailableException(
                    code,
                    error == null ? null : error.retryAfterSeconds()
                );
            }
            metrics.agentCallFinished(sample, "rejected");
            throw new IllegalArgumentException(
                "Agent service rejected the analysis request with code " + code + " and status " + status
            );
        } catch (IllegalArgumentException ex) {
            metrics.agentCallFinished(sample, "invalid_response");
            throw ex;
        } catch (RuntimeException ex) {
            metrics.agentCallFinished(sample, "failure");
            throw ex;
        }
    }

    private AgentErrorResponse readError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(responseBody, AgentErrorResponse.class);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

}

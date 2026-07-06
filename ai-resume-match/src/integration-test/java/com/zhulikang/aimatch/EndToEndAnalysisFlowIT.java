package com.zhulikang.aimatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndAnalysisFlowIT {
    private static final String API_TOKEN = "test-token";
    private static final String REPORT_CONTENT =
        "\u5339\u914d\u5206\u6570: 91\nSummary: integration flow succeeded";

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("ai_resume_match")
        .withUsername("test")
        .withPassword("test");

    @Container
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.2-alpine")
        .withExposedPorts(6379);

    static final MockAiServer aiServer = MockAiServer.start(REPORT_CONTENT);

    @Autowired
    TestRestTemplate restTemplate;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        registry.add("spring.rabbitmq.publisher-confirm-type", () -> "correlated");
        registry.add("spring.rabbitmq.publisher-returns", () -> "true");
        registry.add("spring.rabbitmq.template.mandatory", () -> "true");
        registry.add("api.token", () -> API_TOKEN);
        registry.add("ai.api-key", () -> "test-ai-key");
        registry.add("ai.endpoint", aiServer::endpoint);
        registry.add("ai.model", () -> "test-model");
        registry.add("analysis.outbox.fixed-delay-ms", () -> "100");
        registry.add("analysis.outbox.confirm-timeout", () -> "30s");
        registry.add("analysis.retry.scheduler-fixed-delay-ms", () -> "60000");
        registry.add("spring.autoconfigure.exclude", () -> "");
        registry.add("spring.task.scheduling.enabled", () -> "true");
        registry.add("management.endpoints.web.exposure.include", () -> "health,info,metrics");
    }

    @AfterAll
    static void stopMockAiServer() {
        aiServer.close();
    }

    @Test
    void processesDocxResumeThroughHttpOutboxWorkerAiAndRedisCache() throws Exception {
        FlowResult result = runFlow(
            "docx-flow",
            "resume.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            IntegrationDocumentFixtures.docx("Java Spring Boot Redis RabbitMQ integration candidate")
        );

        assertThat(result.matchScore()).isEqualTo(91);
        assertThat(redisTemplate.hasKey("match-report:" + result.taskId())).isTrue();
    }

    @Test
    void processesPdfResumeThroughHttpOutboxWorkerAiAndMetricsEndpoint() throws Exception {
        int before = aiServer.requestCount();

        FlowResult result = runFlow(
            "pdf-flow",
            "resume.pdf",
            "application/pdf",
            IntegrationDocumentFixtures.pdf("Java Spring Boot Redis RabbitMQ integration candidate")
        );

        assertThat(result.matchScore()).isEqualTo(91);
        assertThat(aiServer.requestCount()).isEqualTo(before + 1);
        assertThat(aiServer.lastAuthorization()).isEqualTo("Bearer test-ai-key");
        assertThat(aiServer.lastBody()).contains("test-model");

        ResponseEntity<String> metrics = restTemplate.getForEntity("/actuator/metrics", String.class);
        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private FlowResult runFlow(String flowName, String filename, String contentType, byte[] content) throws Exception {
        long resumeId = uploadResume(flowName, filename, contentType, content);
        long jobId = createJob(flowName);
        long taskId = createAnalysis(flowName, resumeId, jobId);
        JsonNode report = awaitReport(flowName, taskId);
        awaitTaskSuccess(flowName, taskId);
        return new FlowResult(taskId, report.required("matchScore").asInt());
    }

    private long uploadResume(String flowName, String filename, String contentType, byte[] content) throws Exception {
        HttpHeaders headers = apiHeaders(flowName);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(contentType));
        HttpEntity<ByteArrayResource> filePart = new HttpEntity<>(namedResource(content, filename), fileHeaders);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart);

        ResponseEntity<String> response = restTemplate.exchange(
            "/api/resumes",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo(flowName + "-request");
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(flowName + "-correlation");
        return objectMapper.readTree(response.getBody()).required("resumeId").asLong();
    }

    private long createJob(String flowName) throws Exception {
        HttpHeaders headers = apiHeaders(flowName);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
            "/api/jobs",
            new HttpEntity<>(Map.of("content", "We need Java, Spring Boot, Redis, and RabbitMQ experience."), headers),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).required("jobDescriptionId").asLong();
    }

    private long createAnalysis(String flowName, long resumeId, long jobId) throws Exception {
        HttpHeaders headers = apiHeaders(flowName);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
            "/api/analysis",
            new HttpEntity<>(Map.of("resumeId", resumeId, "jobDescriptionId", jobId), headers),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.required("status").asText()).isEqualTo("PENDING");
        return json.required("taskId").asLong();
    }

    private JsonNode awaitReport(String flowName, long taskId) {
        AtomicReference<JsonNode> capturedReport = new AtomicReference<>();
        Awaitility.await()
            .atMost(Duration.ofSeconds(40))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                ResponseEntity<String> response = getApi(flowName, "/api/analysis/" + taskId + "/report");
                assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
                JsonNode report = objectMapper.readTree(response.getBody());
                assertThat(report.required("taskId").asLong()).isEqualTo(taskId);
                assertThat(report.required("matchScore").asInt()).isEqualTo(91);
                assertThat(report.required("reportContent").asText()).contains("integration flow succeeded");
                capturedReport.set(report);
            });
        return capturedReport.get();
    }

    private void awaitTaskSuccess(String flowName, long taskId) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(250))
            .untilAsserted(() -> {
                ResponseEntity<String> response = getApi(flowName, "/api/analysis/" + taskId);
                assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
                JsonNode task = objectMapper.readTree(response.getBody());
                assertThat(task.required("status").asText()).isEqualTo("SUCCESS");
            });
    }

    private ResponseEntity<String> getApi(String flowName, String path) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(apiHeaders(flowName)), String.class);
    }

    private HttpHeaders apiHeaders(String flowName) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Token", API_TOKEN);
        headers.set("X-Request-Id", flowName + "-request");
        headers.set("X-Correlation-Id", flowName + "-correlation");
        return headers;
    }

    private ByteArrayResource namedResource(byte[] content, String filename) {
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private record FlowResult(long taskId, int matchScore) {
    }
}

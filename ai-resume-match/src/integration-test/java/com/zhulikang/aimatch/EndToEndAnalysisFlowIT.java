package com.zhulikang.aimatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhulikang.aimatch.analysis.MatchReportRepository;
import com.zhulikang.aimatch.analysis.AnalysisOutboxRepository;
import com.zhulikang.aimatch.analysis.AnalysisSubmissionIdempotencyRepository;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.ResumeRepository;
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
import org.springframework.test.annotation.DirtiesContext;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
    @Autowired
    MatchReportRepository reportRepository;
    @Autowired
    AnalysisTaskRepository taskRepository;
    @Autowired
    AnalysisOutboxRepository outboxRepository;
    @Autowired
    AnalysisSubmissionIdempotencyRepository idempotencyRepository;
    @Autowired
    ResumeRepository resumeRepository;
    @Autowired
    JobDescriptionRepository jobRepository;
    private final Map<String, String> sessionCookies = new ConcurrentHashMap<>();

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
        // src/test/resources/application.yml intentionally shadows the production config;
        // repeat the fail-fast cache boundary for this real-infrastructure test context.
        registry.add("spring.data.redis.connect-timeout", () -> "500ms");
        registry.add("spring.data.redis.timeout", () -> "500ms");
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        registry.add("spring.rabbitmq.publisher-confirm-type", () -> "correlated");
        registry.add("spring.rabbitmq.publisher-returns", () -> "true");
        registry.add("spring.rabbitmq.template.mandatory", () -> "true");
        registry.add("api.token", () -> API_TOKEN);
        registry.add("analysis.rate-limit.enabled", () -> "true");
        registry.add("analysis.rate-limit.max-requests", () -> "10");
        registry.add("ai.api-key", () -> "test-ai-key");
        registry.add("ai.endpoint", aiServer::endpoint);
        registry.add("ai.model", () -> "test-model");
        registry.add("analysis.engine", () -> "legacy");
        registry.add("analysis.outbox.fixed-delay-ms", () -> "100");
        registry.add("analysis.outbox.confirm-timeout", () -> "30s");
        registry.add("analysis.outbox.lease-duration", () -> "45s");
        registry.add("analysis.retry.scheduler-fixed-delay-ms", () -> "60000");
        registry.add("spring.autoconfigure.exclude", () -> "");
        registry.add("analysis.scheduling.enabled", () -> "true");
        registry.add("management.endpoints.web.exposure.include", () -> "health,info,metrics");
    }

    @AfterAll
    static void stopMockAiServer() {
        aiServer.close();
    }

    @Test
    void processesDocxResumeThroughAtomicSubmissionOutboxWorkerAiAndRedisCache() throws Exception {
        String flowName = "docx-flow";
        String jobTitle = "Backend Engineer";
        long taskId = createAnalysisSubmission(
            flowName,
            "resume.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            IntegrationDocumentFixtures.docx("Java Spring Boot Redis RabbitMQ integration candidate"),
            jobTitle
        );
        JsonNode report = awaitReport(flowName, taskId);
        awaitTaskSuccess(flowName, taskId, jobTitle, "resume.docx");

        assertThat(report.required("matchScore").asInt()).isEqualTo(91);
        assertThat(redisTemplate.hasKey("match-report:" + taskId)).isTrue();

        ResponseEntity<String> historyResponse = getApi(flowName, "/api/analysis?status=SUCCESS");
        assertThat(historyResponse.getStatusCode()).as(historyResponse.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode historyItems = objectMapper.readTree(historyResponse.getBody()).required("items");
        JsonNode historyTask = null;
        for (JsonNode item : historyItems) {
            if (item.required("taskId").asLong() == taskId) {
                historyTask = item;
                break;
            }
        }
        assertThat(historyTask).isNotNull();
        assertThat(historyTask.required("jobTitle").asText()).isEqualTo(jobTitle);
        assertThat(historyTask.required("resumeFileName").asText()).isEqualTo("resume.docx");

        ResponseEntity<String> summaryResponse = getApi(flowName, "/api/analysis/summary");
        assertThat(summaryResponse.getStatusCode()).as(summaryResponse.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode summary = objectMapper.readTree(summaryResponse.getBody());
        assertThat(summary.required("totalCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(summary.required("averageMatchScore").asDouble()).isEqualTo(91.0);

        ResponseEntity<String> isolatedHistory = getApi("other-browser", "/api/analysis");
        assertThat(isolatedHistory.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(isolatedHistory.getBody()).required("items").size()).isZero();
        ResponseEntity<String> isolatedTask = getApi(
            "other-browser",
            "/api/analysis/" + taskId
        );
        assertThat(isolatedTask.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> isolatedReport = getApi(
            "other-browser",
            "/api/analysis/" + taskId + "/report"
        );
        assertThat(isolatedReport.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> isolatedDelete = deleteApi("other-browser", taskId);
        assertThat(isolatedDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var persistedTask = taskRepository.findById(taskId).orElseThrow();
        Long resumeId = persistedTask.getResumeId();
        Long jobId = persistedTask.getJobDescriptionId();

        ResponseEntity<String> deleted = deleteApi(flowName, taskId);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(deleted.getBody()).isNullOrEmpty();
        assertThat(taskRepository.findById(taskId)).isEmpty();
        assertThat(reportRepository.findByTaskId(taskId)).isEmpty();
        assertThat(resumeRepository.findById(resumeId)).isEmpty();
        assertThat(jobRepository.findById(jobId)).isEmpty();
        assertThat(outboxRepository.findAll())
            .noneMatch(event -> event.getAggregateId().equals(taskId));
        assertThat(idempotencyRepository.findAll())
            .noneMatch(record -> Long.valueOf(taskId).equals(record.getTaskId()));
        assertThat(redisTemplate.hasKey("match-report:" + taskId)).isFalse();
        assertThat(getApi(flowName, "/api/analysis/" + taskId).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getApi(flowName, "/api/analysis/" + taskId + "/report").getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
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

    @Test
    void readsPersistedReportWhileRedisIsUnavailableAndRecachesAfterRecovery() throws Exception {
        FlowResult result = runFlow(
            "redis-outage-flow",
            "redis-outage.pdf",
            "application/pdf",
            IntegrationDocumentFixtures.pdf("Java Redis outage recovery candidate")
        );
        String cacheKey = "match-report:" + result.taskId();
        assertThat(redisTemplate.hasKey(cacheKey)).isTrue();
        assertThat(reportRepository.findByTaskId(result.taskId())).isPresent();
        assertThat(redisTemplate.delete(cacheKey)).isTrue();

        pauseRedisContainer();
        try {
            long requestStartedAt = System.nanoTime();
            ResponseEntity<String> response = getApi(
                "redis-outage-flow",
                "/api/analysis/" + result.taskId() + "/report"
            );
            long requestDurationMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - requestStartedAt
            );
            assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(requestDurationMillis).isLessThan(3_000L);
            JsonNode report = objectMapper.readTree(response.getBody());
            assertThat(report.required("taskId").asLong()).isEqualTo(result.taskId());
            assertThat(report.required("matchScore").asInt()).isEqualTo(91);
            assertThat(report.required("reportContent").asText())
                .contains("integration flow succeeded");
            assertThat(reportRepository.findByTaskId(result.taskId())).isPresent();
        } finally {
            unpauseRedisContainer();
        }

        Awaitility.await()
            .atMost(Duration.ofSeconds(30))
            .ignoreExceptions()
            .untilAsserted(() -> {
                ResponseEntity<String> recoveredResponse = getApi(
                    "redis-outage-flow",
                    "/api/analysis/" + result.taskId() + "/report"
                );
                assertThat(recoveredResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(redisTemplate.hasKey(cacheKey)).isTrue();
            });
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
        rememberSession(flowName, response);
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
        rememberSession(flowName, response);
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

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        rememberSession(flowName, response);
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.required("status").asText()).isEqualTo("PENDING");
        long taskId = json.required("taskId").asLong();
        assertThat(response.getHeaders().getLocation())
            .hasToString("/api/analysis/" + taskId);
        return taskId;
    }

    private long createAnalysisSubmission(
        String flowName,
        String filename,
        String contentType,
        byte[] content,
        String jobTitle
    ) throws Exception {
        HttpHeaders headers = apiHeaders(flowName);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", flowName + "-submission");

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(contentType));
        HttpEntity<ByteArrayResource> filePart = new HttpEntity<>(namedResource(content, filename), fileHeaders);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart);
        body.add("jobTitle", jobTitle);
        body.add("jobContent", "We need Java, Spring Boot, Redis, and RabbitMQ experience.");

        ResponseEntity<String> response = restTemplate.exchange(
            "/api/analysis-submissions",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        rememberSession(flowName, response);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo(flowName + "-request");
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(flowName + "-correlation");
        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.required("status").asText()).isEqualTo("PENDING");
        assertThat(json.required("jobTitle").asText()).isEqualTo(jobTitle);
        assertThat(json.required("resumeFileName").asText()).isEqualTo(filename);
        long taskId = json.required("taskId").asLong();
        assertThat(response.getHeaders().getLocation())
            .hasToString("/api/analysis/" + taskId);
        return taskId;
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

    private void pauseRedisContainer() {
        redis.getDockerClient()
            .pauseContainerCmd(redis.getContainerId())
            .exec();
    }

    private void unpauseRedisContainer() {
        redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
        Awaitility.await()
            .atMost(Duration.ofSeconds(15))
            .ignoreExceptions()
            .untilAsserted(() -> {
                assertThat(redis.getDockerClient()
                    .inspectContainerCmd(redis.getContainerId())
                    .exec()
                    .getState()
                    .getRunning()).isTrue();
                assertThat(redis.getDockerClient()
                    .inspectContainerCmd(redis.getContainerId())
                    .exec()
                    .getState()
                    .getPaused()).isFalse();
                assertThat(redis.execInContainer("redis-cli", "ping").getStdout()).contains("PONG");
            });
    }

    private void awaitTaskSuccess(String flowName, long taskId) {
        awaitTaskSuccess(flowName, taskId, null, null);
    }

    private void awaitTaskSuccess(
        String flowName,
        long taskId,
        String expectedJobTitle,
        String expectedResumeFileName
    ) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(250))
            .untilAsserted(() -> {
                ResponseEntity<String> response = getApi(flowName, "/api/analysis/" + taskId);
                assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
                JsonNode task = objectMapper.readTree(response.getBody());
                assertThat(task.required("status").asText()).isEqualTo("SUCCESS");
                if (expectedJobTitle != null) {
                    assertThat(task.required("jobTitle").asText()).isEqualTo(expectedJobTitle);
                }
                if (expectedResumeFileName != null) {
                    assertThat(task.required("resumeFileName").asText()).isEqualTo(expectedResumeFileName);
                }
            });
    }

    private ResponseEntity<String> getApi(String flowName, String path) {
        ResponseEntity<String> response = restTemplate.exchange(
            path,
            HttpMethod.GET,
            new HttpEntity<>(apiHeaders(flowName)),
            String.class
        );
        rememberSession(flowName, response);
        return response;
    }

    private HttpHeaders apiHeaders(String flowName) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Token", API_TOKEN);
        headers.set("X-Request-Id", flowName + "-request");
        headers.set("X-Correlation-Id", flowName + "-correlation");
        String sessionCookie = sessionCookies.get(flowName);
        if (sessionCookie != null) {
            headers.set(HttpHeaders.COOKIE, sessionCookie);
        }
        return headers;
    }

    private ResponseEntity<String> deleteApi(String flowName, long taskId) {
        ResponseEntity<String> response = restTemplate.exchange(
            "/api/analysis/" + taskId,
            HttpMethod.DELETE,
            new HttpEntity<>(apiHeaders(flowName)),
            String.class
        );
        rememberSession(flowName, response);
        return response;
    }

    private void rememberSession(String flowName, ResponseEntity<?> response) {
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        if (setCookie != null) {
            sessionCookies.putIfAbsent(flowName, setCookie.split(";", 2)[0]);
        }
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

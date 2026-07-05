# Phase 5 Observability and Operations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add request/correlation IDs, structured task lifecycle logs, Micrometer metrics, and operations documentation so analysis failures can be diagnosed without logging resume, JD, prompt, or AI response content.

**Architecture:** Keep the Spring Boot monolith and current package boundaries. Add a small `observability` package for request correlation and metrics helpers, then inject those helpers into API, analysis, AI, cache, and outbox code. Do not add external dashboards, tracing backends, accounts, frontend, or new infrastructure services in this phase.

**Tech Stack:** Spring Boot 3.3, Java 21, Spring MVC filter/interceptor chain, SLF4J MDC, Micrometer via Actuator, JUnit 5, AssertJ, Mockito, MockMvc, OutputCaptureExtension.

---

## Covered Scope

- Add `X-Request-Id` and `X-Correlation-Id` handling for all HTTP requests.
- Add `requestId` to structured API error responses.
- Preserve incoming request/correlation IDs when valid, generate UUID values when missing or invalid, and return them in response headers.
- Store correlation ID in outbox payload and publish it as a RabbitMQ message header.
- Restore correlation ID into MDC inside `AnalysisWorker`.
- Replace low-context task logs with structured key-value logs for start, skip, success, retryable failure, and final failure.
- Add Micrometer counters/timers for analysis tasks, AI calls, Redis cache, and outbox publishing.
- Add outbox backlog gauges by outbox status.
- Expose Actuator `metrics` endpoint.
- Update README, architecture docs, development guide, and runbook with current observability behavior and commands.

## Out of Scope

- No OpenTelemetry exporter, Prometheus registry dependency, Grafana dashboard, or distributed tracing backend.
- No RabbitMQ queue-depth polling implementation; document RabbitMQ management checks instead.
- No schema migration for correlation ID storage; correlation lives in outbox payload/message headers for this phase.
- No logging of raw resume text, JD content, prompt text, or AI response body.

---

## File Map

- Create `src/main/java/com/zhulikang/aimatch/observability/RequestCorrelation.java`: constants and helpers for request/correlation IDs and MDC.
- Create `src/main/java/com/zhulikang/aimatch/observability/RequestCorrelationFilter.java`: once-per-request filter.
- Create `src/main/java/com/zhulikang/aimatch/observability/AnalysisMetrics.java`: counters/timers for analysis, AI, cache, and outbox events.
- Create `src/main/java/com/zhulikang/aimatch/observability/OutboxMetrics.java`: outbox status gauges.
- Modify `src/main/java/com/zhulikang/aimatch/api/ApiErrorResponse.java`: include `requestId`.
- Modify `src/main/java/com/zhulikang/aimatch/api/ApiExceptionHandler.java`: keep using `ApiErrorResponse` with current request ID.
- Modify `src/main/java/com/zhulikang/aimatch/api/ApiTokenInterceptor.java`: unauthorized response includes request ID through `ApiErrorResponse`.
- Modify `src/main/java/com/zhulikang/aimatch/application/analysis/AnalysisTaskPublisher.java`: persist current correlation ID in outbox payload.
- Modify `src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxEvent.java`: overload `analysisRequested` with correlation ID.
- Modify `src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxPublisher.java`: publish RabbitMQ headers and outbox metrics.
- Modify `src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxRepository.java`: add `countByStatus`.
- Modify `src/main/java/com/zhulikang/aimatch/analysis/AnalysisWorker.java`: restore correlation ID from RabbitMQ headers.
- Modify `src/main/java/com/zhulikang/aimatch/application/analysis/CreateAnalysisTaskUseCase.java`: task-created metric.
- Modify `src/main/java/com/zhulikang/aimatch/application/analysis/RunAnalysisUseCase.java`: structured lifecycle logs and task metrics.
- Modify `src/main/java/com/zhulikang/aimatch/ai/OpenAiCompatibleClient.java`: AI call metrics.
- Modify `src/main/java/com/zhulikang/aimatch/analysis/RedisReportCache.java`: cache hit/miss/error metrics.
- Modify `src/main/resources/application.yml`: expose Actuator `metrics`.
- Modify tests under `src/test/java`: add/adjust API, publisher, worker, use-case, AI, cache, config, and observability tests.
- Modify docs: `README.md`, `docs/operations/runbook.md`, `docs/development.md`, `docs/architecture.md`.

---

## Task 1: Request and Error Correlation

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/observability/RequestCorrelation.java`
- Create: `src/main/java/com/zhulikang/aimatch/observability/RequestCorrelationFilter.java`
- Modify: `src/main/java/com/zhulikang/aimatch/api/ApiErrorResponse.java`
- Modify: `src/main/java/com/zhulikang/aimatch/api/ResumeMatchController.java`
- Modify: `src/test/java/com/zhulikang/aimatch/api/ResumeMatchControllerTest.java`

- [x] **Step 1: Write failing API correlation tests**

Add imports to `ResumeMatchControllerTest`:

```java
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
```

Add these tests:

```java
@Test
void addsGeneratedRequestIdToUnauthorizedErrorResponse() throws Exception {
    mockMvc.perform(get("/api/analysis/1/report"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(header().exists("X-Correlation-Id"))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.message").value("Unauthorized"))
        .andExpect(jsonPath("$.requestId").isNotEmpty());
}

@Test
void reusesIncomingRequestAndCorrelationIds() throws Exception {
    when(getAnalysisTaskUseCase.find(404L)).thenReturn(Optional.empty());

    mockMvc.perform(get("/api/analysis/404")
            .header("X-API-Token", "test-token")
            .header("X-Request-Id", "client-request-1")
            .header("X-Correlation-Id", "client-correlation-1"))
        .andExpect(status().isNotFound())
        .andExpect(header().string("X-Request-Id", "client-request-1"))
        .andExpect(header().string("X-Correlation-Id", "client-correlation-1"))
        .andExpect(jsonPath("$.requestId").value("client-request-1"));
}
```

- [x] **Step 2: Run the tests and verify RED**

```powershell
mvn "-Dtest=ResumeMatchControllerTest#addsGeneratedRequestIdToUnauthorizedErrorResponse+reusesIncomingRequestAndCorrelationIds" test
```

Expected: FAIL because response headers and `requestId` are not present.

- [x] **Step 3: Add request correlation helpers and filter**

Create `RequestCorrelation.java`:

```java
package com.zhulikang.aimatch.observability;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

public final class RequestCorrelation {
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String REQUEST_ID_MDC_KEY = "requestId";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";
    public static final String REQUEST_ID_ATTRIBUTE =
        RequestCorrelation.class.getName() + ".requestId";
    public static final String CORRELATION_ID_ATTRIBUTE =
        RequestCorrelation.class.getName() + ".correlationId";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private RequestCorrelation() {
    }

    public static String safeOrNew(String candidate) {
        if (candidate != null && SAFE_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }

    public static String currentRequestId() {
        return MDC.get(REQUEST_ID_MDC_KEY);
    }

    public static String currentCorrelationId() {
        return MDC.get(CORRELATION_ID_MDC_KEY);
    }

    public static String currentCorrelationIdOrNew() {
        String correlationId = currentCorrelationId();
        return correlationId == null || correlationId.isBlank() ? UUID.randomUUID().toString() : correlationId;
    }

    public static void put(String requestId, String correlationId) {
        if (requestId != null && !requestId.isBlank()) {
            MDC.put(REQUEST_ID_MDC_KEY, requestId);
        }
        if (correlationId != null && !correlationId.isBlank()) {
            MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        }
    }

    public static void clear() {
        MDC.remove(REQUEST_ID_MDC_KEY);
        MDC.remove(CORRELATION_ID_MDC_KEY);
    }
}
```

Create `RequestCorrelationFilter.java`:

```java
package com.zhulikang.aimatch.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = RequestCorrelation.safeOrNew(request.getHeader(RequestCorrelation.REQUEST_ID_HEADER));
        String correlationId = RequestCorrelation.safeOrNew(request.getHeader(RequestCorrelation.CORRELATION_ID_HEADER));
        request.setAttribute(RequestCorrelation.REQUEST_ID_ATTRIBUTE, requestId);
        request.setAttribute(RequestCorrelation.CORRELATION_ID_ATTRIBUTE, correlationId);
        response.setHeader(RequestCorrelation.REQUEST_ID_HEADER, requestId);
        response.setHeader(RequestCorrelation.CORRELATION_ID_HEADER, correlationId);
        RequestCorrelation.put(requestId, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestCorrelation.clear();
        }
    }
}
```

- [x] **Step 4: Include request ID in API errors**

Replace `ApiErrorResponse` with:

```java
package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.observability.RequestCorrelation;

public record ApiErrorResponse(String code, String message, String requestId) {
    public ApiErrorResponse(String code, String message) {
        this(code, message, RequestCorrelation.currentRequestId());
    }
}
```

- [x] **Step 5: Run focused tests and verify GREEN**

```powershell
mvn "-Dtest=ResumeMatchControllerTest#addsGeneratedRequestIdToUnauthorizedErrorResponse+reusesIncomingRequestAndCorrelationIds" test
```

Expected: PASS.

- [x] **Step 6: Run all API tests**

```powershell
mvn "-Dtest=ResumeMatchControllerTest" test
```

Expected: PASS.

- [x] **Step 7: Commit**

```powershell
git add src/main/java/com/zhulikang/aimatch/observability/RequestCorrelation.java src/main/java/com/zhulikang/aimatch/observability/RequestCorrelationFilter.java src/main/java/com/zhulikang/aimatch/api/ApiErrorResponse.java src/test/java/com/zhulikang/aimatch/api/ResumeMatchControllerTest.java
git commit -m "feat: add request correlation ids"
```

---

## Task 2: Correlation Propagation Through Outbox and Worker

**Files:**
- Modify: `src/main/java/com/zhulikang/aimatch/application/analysis/AnalysisTaskPublisher.java`
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxEvent.java`
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxPublisher.java`
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisWorker.java`
- Modify: `src/test/java/com/zhulikang/aimatch/application/analysis/AnalysisTaskPublisherTest.java`
- Modify: `src/test/java/com/zhulikang/aimatch/analysis/AnalysisOutboxPublisherTest.java`
- Modify: `src/test/java/com/zhulikang/aimatch/analysis/AnalysisWorkerTest.java`

- [x] **Step 1: Write failing publisher correlation tests**

Add to `AnalysisTaskPublisherTest`:

```java
import com.zhulikang.aimatch.observability.RequestCorrelation;
```

Add this test:

```java
@Test
void persistsCurrentCorrelationIdInOutboxPayload() {
    AnalysisOutboxRepository outboxRepository = mock(AnalysisOutboxRepository.class);
    AnalysisTaskPublisher publisher = new AnalysisTaskPublisher(outboxRepository);
    RequestCorrelation.put("request-1", "correlation-1");
    try {
        publisher.publishAfterCommit(99L);
    } finally {
        RequestCorrelation.clear();
    }

    ArgumentCaptor<AnalysisOutboxEvent> eventCaptor = ArgumentCaptor.forClass(AnalysisOutboxEvent.class);
    verify(outboxRepository).save(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getPayloadJson())
        .contains("\"taskId\":99")
        .contains("\"correlationId\":\"correlation-1\"");
}
```

Add to `AnalysisOutboxPublisherTest`:

```java
import org.springframework.amqp.core.MessagePostProcessor;
```

Add this test:

```java
@Test
void publishesCorrelationIdAsRabbitHeader() {
    AnalysisOutboxRepository repository = mock(AnalysisOutboxRepository.class);
    RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    AnalysisOutboxEvent event = AnalysisOutboxEvent.analysisRequested(99L, "correlation-1");
    when(repository.findDueForPublishIds(eq(CLAIMABLE_STATUSES), eq(now), any(Pageable.class)))
        .thenReturn(List.of(10L));
    when(repository.markProcessingIfDue(
        10L,
        CLAIMABLE_STATUSES,
        now,
        AnalysisOutboxStatus.PROCESSING,
        now.plusSeconds(30)
    )).thenReturn(1);
    when(repository.findById(10L)).thenReturn(Optional.of(event));
    completePublishWithAck(rabbitTemplate);
    AnalysisOutboxPublisher publisher = new AnalysisOutboxPublisher(
        repository,
        rabbitTemplate,
        20,
        Duration.ofSeconds(30),
        Duration.ofSeconds(5),
        clock
    );

    publisher.publishPending();

    ArgumentCaptor<MessagePostProcessor> processorCaptor = ArgumentCaptor.forClass(MessagePostProcessor.class);
    verify(rabbitTemplate).convertAndSend(
        eq(RabbitConfig.ANALYSIS_EXCHANGE),
        eq(RabbitConfig.ANALYSIS_ROUTING_KEY),
        eq(99L),
        processorCaptor.capture(),
        any(CorrelationData.class)
    );
    Message message = new Message(new byte[0], new MessageProperties());
    Message processed = processorCaptor.getValue().postProcessMessage(message);
    assertThat(processed.getMessageProperties().getHeader("X-Correlation-Id")).isEqualTo("correlation-1");
    assertThat(processed.getMessageProperties().getHeader("analysisOutboxEventId")).isEqualTo(10L);
}
```

- [x] **Step 2: Run tests and verify RED**

```powershell
mvn "-Dtest=AnalysisTaskPublisherTest,AnalysisOutboxPublisherTest" test
```

Expected: FAIL because payload correlation and Rabbit headers do not exist.

- [x] **Step 3: Add outbox payload correlation ID**

In `AnalysisTaskPublisher`, inject current correlation:

```java
import com.zhulikang.aimatch.observability.RequestCorrelation;

public void publishAfterCommit(Long taskId) {
    outboxRepository.save(AnalysisOutboxEvent.analysisRequested(
        taskId,
        RequestCorrelation.currentCorrelationIdOrNew()
    ));
}
```

In `AnalysisOutboxEvent`, add overload and safe payload builder:

```java
public static AnalysisOutboxEvent analysisRequested(Long taskId) {
    return analysisRequested(taskId, null);
}

public static AnalysisOutboxEvent analysisRequested(Long taskId, String correlationId) {
    return new AnalysisOutboxEvent(
        AnalysisOutboxEventType.ANALYSIS_REQUESTED,
        "analysis_task",
        taskId,
        payload(taskId, correlationId)
    );
}

private static String payload(Long taskId, String correlationId) {
    if (correlationId == null || correlationId.isBlank()) {
        return "{\"taskId\":" + taskId + "}";
    }
    return "{\"taskId\":" + taskId + ",\"correlationId\":\"" + correlationId + "\"}";
}
```

- [x] **Step 4: Publish Rabbit headers and update test helper**

In `AnalysisOutboxPublisher`, add imports:

```java
import com.zhulikang.aimatch.observability.RequestCorrelation;
import org.springframework.amqp.core.MessagePostProcessor;
```

Change `publishToRabbit`:

```java
private void publishToRabbit(Long eventId, Long taskId, String correlationId) throws Exception {
    CorrelationData correlationData = new CorrelationData("analysis-outbox-" + eventId);
    MessagePostProcessor headers = message -> {
        message.getMessageProperties().setHeader("analysisOutboxEventId", eventId);
        if (correlationId != null && !correlationId.isBlank()) {
            message.getMessageProperties().setHeader(RequestCorrelation.CORRELATION_ID_HEADER, correlationId);
            message.getMessageProperties().setCorrelationId(correlationId);
        }
        return message;
    };
    rabbitTemplate.convertAndSend(
        RabbitConfig.ANALYSIS_EXCHANGE,
        RabbitConfig.ANALYSIS_ROUTING_KEY,
        taskId,
        headers,
        correlationData
    );
    Confirm confirm = correlationData.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
    if (!confirm.isAck()) {
        throw new AmqpException("RabbitMQ broker did not confirm publish: " + confirm.getReason());
    }
    ReturnedMessage returned = correlationData.getReturned();
    if (returned != null) {
        throw new AmqpException(
            "RabbitMQ returned unroutable message: " + returned.getReplyText()
                + " exchange=" + returned.getExchange()
                + " routingKey=" + returned.getRoutingKey()
        );
    }
}
```

Add:

```java
private String extractCorrelationId(AnalysisOutboxEvent event) throws Exception {
    return objectMapper.readTree(event.getPayloadJson()).path("correlationId").asText(null);
}
```

Change the publish call:

```java
Long taskId = extractTaskId(event);
String correlationId = extractCorrelationId(event);
publishToRabbit(eventId, taskId, correlationId);
```

Update `AnalysisOutboxPublisherTest.completePublishWithAck` and failure stubs to use the five-argument `convertAndSend` overload with `any(MessagePostProcessor.class)`.

- [x] **Step 5: Restore correlation ID in worker**

Add this test to `AnalysisWorkerTest`:

```java
@Test
void restoresCorrelationIdFromRabbitHeader() {
    RunAnalysisUseCase useCase = mock(RunAnalysisUseCase.class);
    AnalysisWorker worker = new AnalysisWorker(useCase);

    worker.handle(99L, false, "correlation-1");

    verify(useCase).run(99L, false);
    assertThat(RequestCorrelation.currentCorrelationId()).isNull();
}
```

Modify `AnalysisWorker`:

```java
@RabbitListener(queues = RabbitConfig.ANALYSIS_QUEUE)
public void handle(
    Long taskId,
    @Header(name = AmqpHeaders.REDELIVERED, required = false) Boolean redelivered,
    @Header(name = RequestCorrelation.CORRELATION_ID_HEADER, required = false) String correlationId
) {
    String safeCorrelationId = RequestCorrelation.safeOrNew(correlationId);
    RequestCorrelation.put(null, safeCorrelationId);
    try {
        runAnalysisUseCase.run(taskId, Boolean.TRUE.equals(redelivered));
    } finally {
        RequestCorrelation.clear();
    }
}

public void handle(Long taskId, Boolean redelivered) {
    handle(taskId, redelivered, null);
}
```

- [x] **Step 6: Run focused tests**

```powershell
mvn "-Dtest=AnalysisTaskPublisherTest,AnalysisOutboxPublisherTest,AnalysisWorkerTest" test
```

Expected: PASS.

- [x] **Step 7: Commit**

```powershell
git add src/main/java/com/zhulikang/aimatch/application/analysis/AnalysisTaskPublisher.java src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxEvent.java src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxPublisher.java src/main/java/com/zhulikang/aimatch/analysis/AnalysisWorker.java src/test/java/com/zhulikang/aimatch/application/analysis/AnalysisTaskPublisherTest.java src/test/java/com/zhulikang/aimatch/analysis/AnalysisOutboxPublisherTest.java src/test/java/com/zhulikang/aimatch/analysis/AnalysisWorkerTest.java
git commit -m "feat: propagate analysis correlation ids"
```

---

## Task 3: Analysis Metrics and Structured Task Logs

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/observability/AnalysisMetrics.java`
- Modify: `src/main/java/com/zhulikang/aimatch/application/analysis/CreateAnalysisTaskUseCase.java`
- Modify: `src/main/java/com/zhulikang/aimatch/application/analysis/RunAnalysisUseCase.java`
- Modify: `src/test/java/com/zhulikang/aimatch/application/analysis/CreateAnalysisTaskUseCaseTest.java`
- Modify: `src/test/java/com/zhulikang/aimatch/application/analysis/RunAnalysisUseCaseTest.java`

- [x] **Step 1: Write failing metrics and log tests**

In `CreateAnalysisTaskUseCaseTest`, add imports:

```java
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
```

Add assertion to `savesAnalysisTaskAndPublishesTaskIdAfterCommit`:

```java
SimpleMeterRegistry registry = new SimpleMeterRegistry();
AnalysisMetrics metrics = new AnalysisMetrics(registry);
CreateAnalysisTaskUseCase useCase = new CreateAnalysisTaskUseCase(
    resumeRepository,
    jobRepository,
    taskRepository,
    publisher,
    metrics
);
...
assertThat(registry.counter("analysis.tasks.created").count()).isEqualTo(1.0);
```

In `RunAnalysisUseCaseTest`, add imports:

```java
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
```

Annotate the class:

```java
@ExtendWith(OutputCaptureExtension.class)
class RunAnalysisUseCaseTest {
```

Add fields:

```java
private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
private final AnalysisMetrics metrics = new AnalysisMetrics(meterRegistry);
```

Add to `createsReportAndMarksTaskSuccess`:

```java
assertThat(meterRegistry.counter("analysis.tasks.succeeded").count()).isEqualTo(1.0);
assertThat(meterRegistry.find("analysis.worker.duration").timer()).isNotNull();
```

Add a log-focused test:

```java
@Test
void writesStructuredTaskLifecycleLogsWithoutSourceContent(CapturedOutput output) {
    AnalysisTask task = task(99L);
    when(taskService.tryStart(99L, false)).thenReturn(true);
    when(taskRepository.findById(99L)).thenReturn(Optional.of(task));
    when(resumeRepository.findById(1L)).thenReturn(Optional.of(new Resume("resume.docx", "Sensitive resume text", "summary")));
    when(jobRepository.findById(2L)).thenReturn(Optional.of(new JobDescription("Sensitive JD text", "Redis")));
    when(aiClient.complete(anyString())).thenReturn("score 88 report");
    when(reportParser.extractScore("score 88 report")).thenReturn(88);

    useCase().run(99L, false);

    assertThat(output.getOut())
        .contains("event=analysis_task_started")
        .contains("event=analysis_task_succeeded")
        .contains("taskId=99")
        .doesNotContain("Sensitive resume text")
        .doesNotContain("Sensitive JD text")
        .doesNotContain("score 88 report");
}
```

- [x] **Step 2: Run tests and verify RED**

```powershell
mvn "-Dtest=CreateAnalysisTaskUseCaseTest,RunAnalysisUseCaseTest" test
```

Expected: FAIL because metrics helper and structured logs do not exist.

- [x] **Step 3: Implement `AnalysisMetrics`**

Create `AnalysisMetrics.java`:

```java
package com.zhulikang.aimatch.observability;

import com.zhulikang.aimatch.analysis.AnalysisFailureCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class AnalysisMetrics {
    private final MeterRegistry meterRegistry;

    public AnalysisMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void taskCreated() {
        Counter.builder("analysis.tasks.created").register(meterRegistry).increment();
    }

    public void taskSucceeded(Timer.Sample sample) {
        Counter.builder("analysis.tasks.succeeded").register(meterRegistry).increment();
        stopWorker(sample, "success", "none");
    }

    public void taskFailed(AnalysisFailureCode failureCode, Timer.Sample sample) {
        String code = failureCode == null ? "UNKNOWN" : failureCode.name();
        Counter.builder("analysis.tasks.failed")
            .tag("failureCode", code)
            .register(meterRegistry)
            .increment();
        stopWorker(sample, "failure", code);
    }

    public void outboxPublished() {
        Counter.builder("analysis.outbox.events").tag("outcome", "published").register(meterRegistry).increment();
    }

    public void outboxFailed() {
        Counter.builder("analysis.outbox.events").tag("outcome", "failed").register(meterRegistry).increment();
    }

    public void cacheRequest(String result) {
        Counter.builder("report.cache.requests").tag("result", result).register(meterRegistry).increment();
    }

    public void cacheWrite(String outcome) {
        Counter.builder("report.cache.writes").tag("outcome", outcome).register(meterRegistry).increment();
    }

    public Timer.Sample startAiCall() {
        return Timer.start(meterRegistry);
    }

    public void aiCallFinished(Timer.Sample sample, String outcome) {
        Counter.builder("ai.calls").tag("outcome", outcome).register(meterRegistry).increment();
        sample.stop(Timer.builder("ai.call.duration").tag("outcome", outcome).register(meterRegistry));
    }

    private void stopWorker(Timer.Sample sample, String outcome, String failureCode) {
        if (sample != null) {
            sample.stop(Timer.builder("analysis.worker.duration")
                .tag("outcome", outcome)
                .tag("failureCode", failureCode)
                .register(meterRegistry));
        }
    }
}
```

- [x] **Step 4: Record task-created metrics**

Modify `CreateAnalysisTaskUseCase` constructor to accept `AnalysisMetrics metrics` and call:

```java
metrics.taskCreated();
```

immediately after the task is saved and outbox event is written.

Update all `CreateAnalysisTaskUseCaseTest` constructors to pass `new AnalysisMetrics(new SimpleMeterRegistry())`.

- [x] **Step 5: Add structured task logs and run metrics**

Modify `RunAnalysisUseCase` constructor to accept `AnalysisMetrics metrics`.

In `run`, start a timer after `tryStart` succeeds:

```java
Timer.Sample sample = metrics.startTimer();
log.info("event=analysis_task_started taskId={} redelivered={}", taskId, redelivered);
```

On success:

```java
metrics.taskSucceeded(sample);
log.info(
    "event=analysis_task_succeeded taskId={} resumeId={} jobDescriptionId={} attempt={}",
    task.getId(),
    task.getResumeId(),
    task.getJobDescriptionId(),
    task.getAttemptCount()
);
```

On final source-data failure:

```java
metrics.taskFailed(AnalysisFailureCode.SOURCE_DATA_MISSING, sample);
log.warn("event=analysis_task_failed taskId={} failureCode={} retryable=false", taskId, AnalysisFailureCode.SOURCE_DATA_MISSING);
```

On parse failure:

```java
metrics.taskFailed(AnalysisFailureCode.REPORT_PARSE_FAILED, sample);
log.warn("event=analysis_task_failed taskId={} failureCode={} retryable=false", taskId, AnalysisFailureCode.REPORT_PARSE_FAILED);
```

On runtime retryable failure:

```java
metrics.taskFailed(AnalysisFailureCode.AI_UNAVAILABLE, sample);
log.warn("event=analysis_task_failed taskId={} failureCode={} retryable=true", taskId, AnalysisFailureCode.AI_UNAVAILABLE);
```

Keep the skip log structured:

```java
log.info("event=analysis_task_skipped taskId={} redelivered={} reason=not_claimable", taskId, redelivered);
```

Update `RunAnalysisUseCaseTest.useCase()` to pass `metrics`.

- [x] **Step 6: Run focused tests**

```powershell
mvn "-Dtest=CreateAnalysisTaskUseCaseTest,RunAnalysisUseCaseTest" test
```

Expected: PASS.

- [x] **Step 7: Commit**

```powershell
git add src/main/java/com/zhulikang/aimatch/observability/AnalysisMetrics.java src/main/java/com/zhulikang/aimatch/application/analysis/CreateAnalysisTaskUseCase.java src/main/java/com/zhulikang/aimatch/application/analysis/RunAnalysisUseCase.java src/test/java/com/zhulikang/aimatch/application/analysis/CreateAnalysisTaskUseCaseTest.java src/test/java/com/zhulikang/aimatch/application/analysis/RunAnalysisUseCaseTest.java
git commit -m "feat: add analysis lifecycle metrics"
```

---

## Task 4: AI, Cache, and Outbox Metrics

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/observability/OutboxMetrics.java`
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxRepository.java`
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxPublisher.java`
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/RedisReportCache.java`
- Modify: `src/main/java/com/zhulikang/aimatch/ai/OpenAiCompatibleClient.java`
- Modify: `src/test/java/com/zhulikang/aimatch/analysis/AnalysisOutboxPublisherTest.java`
- Modify: `src/test/java/com/zhulikang/aimatch/analysis/RedisReportCacheTest.java`
- Modify: `src/test/java/com/zhulikang/aimatch/ai/OpenAiCompatibleClientTest.java`
- Create: `src/test/java/com/zhulikang/aimatch/observability/OutboxMetricsTest.java`

- [x] **Step 1: Write failing metrics tests**

In `AnalysisOutboxPublisherTest`, use a `SimpleMeterRegistry` and `AnalysisMetrics` when constructing the publisher. Add assertions:

```java
assertThat(meterRegistry.counter("analysis.outbox.events", "outcome", "published").count()).isEqualTo(1.0);
```

in the publish success test, and:

```java
assertThat(meterRegistry.counter("analysis.outbox.events", "outcome", "failed").count()).isEqualTo(1.0);
```

in the failed publish test.

In `RedisReportCacheTest`, create the cache with `AnalysisMetrics` and assert hit/miss/error/write counters:

```java
assertThat(meterRegistry.counter("report.cache.requests", "result", "miss").count()).isEqualTo(1.0);
assertThat(meterRegistry.counter("report.cache.requests", "result", "hit").count()).isEqualTo(1.0);
assertThat(meterRegistry.counter("report.cache.writes", "outcome", "success").count()).isEqualTo(1.0);
```

In `OpenAiCompatibleClientTest`, create the client with `AnalysisMetrics` and assert:

```java
assertThat(meterRegistry.counter("ai.calls", "outcome", "success").count()).isEqualTo(1.0);
assertThat(meterRegistry.find("ai.call.duration").tag("outcome", "success").timer()).isNotNull();
```

Create `OutboxMetricsTest.java`:

```java
package com.zhulikang.aimatch.observability;

import com.zhulikang.aimatch.analysis.AnalysisOutboxRepository;
import com.zhulikang.aimatch.analysis.AnalysisOutboxStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxMetricsTest {
    @Test
    void exposesOutboxBacklogGaugesByStatus() {
        AnalysisOutboxRepository repository = mock(AnalysisOutboxRepository.class);
        when(repository.countByStatus(AnalysisOutboxStatus.PENDING)).thenReturn(3L);
        when(repository.countByStatus(AnalysisOutboxStatus.FAILED)).thenReturn(2L);
        when(repository.countByStatus(AnalysisOutboxStatus.PROCESSING)).thenReturn(1L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new OutboxMetrics(repository, registry);

        assertThat(registry.get("analysis.outbox.backlog").tag("status", "pending").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("analysis.outbox.backlog").tag("status", "failed").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("analysis.outbox.backlog").tag("status", "processing").gauge().value()).isEqualTo(1.0);
    }
}
```

- [x] **Step 2: Run tests and verify RED**

```powershell
mvn "-Dtest=AnalysisOutboxPublisherTest,RedisReportCacheTest,OpenAiCompatibleClientTest,OutboxMetricsTest" test
```

Expected: FAIL because metrics are not recorded and outbox gauge component does not exist.

- [x] **Step 3: Add outbox counters and gauges**

Modify `AnalysisOutboxRepository`:

```java
long countByStatus(AnalysisOutboxStatus status);
```

Create `OutboxMetrics.java`:

```java
package com.zhulikang.aimatch.observability;

import com.zhulikang.aimatch.analysis.AnalysisOutboxRepository;
import com.zhulikang.aimatch.analysis.AnalysisOutboxStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class OutboxMetrics {
    public OutboxMetrics(AnalysisOutboxRepository repository, MeterRegistry meterRegistry) {
        register(repository, meterRegistry, AnalysisOutboxStatus.PENDING, "pending");
        register(repository, meterRegistry, AnalysisOutboxStatus.FAILED, "failed");
        register(repository, meterRegistry, AnalysisOutboxStatus.PROCESSING, "processing");
    }

    private void register(
        AnalysisOutboxRepository repository,
        MeterRegistry meterRegistry,
        AnalysisOutboxStatus status,
        String tagValue
    ) {
        Gauge.builder("analysis.outbox.backlog", repository, repo -> repo.countByStatus(status))
            .tag("status", tagValue)
            .register(meterRegistry);
    }
}
```

Modify `AnalysisOutboxPublisher` constructor to accept `AnalysisMetrics metrics`, default constructor to pass the bean, tests to pass `new AnalysisMetrics(meterRegistry)`, and call:

```java
metrics.outboxPublished();
```

after `event.markPublished(now)`, and:

```java
metrics.outboxFailed();
```

inside both failure branches.

- [x] **Step 4: Add Redis cache metrics**

Modify `RedisReportCache` constructor to accept `AnalysisMetrics metrics`.

In `get`:

```java
if (value == null || value.isBlank()) {
    metrics.cacheRequest("miss");
    return Optional.empty();
}
MatchReportView report = objectMapper.readValue(value, MatchReportView.class);
metrics.cacheRequest("hit");
return Optional.of(report);
```

In catch:

```java
metrics.cacheRequest("error");
```

In `put` success:

```java
metrics.cacheWrite("success");
```

In `put` catch:

```java
metrics.cacheWrite("error");
```

- [x] **Step 5: Add AI call metrics**

Modify `OpenAiCompatibleClient` constructors to accept `AnalysisMetrics metrics`.

In `complete`:

```java
Timer.Sample sample = metrics.startAiCall();
try {
    ... existing call ...
    metrics.aiCallFinished(sample, "success");
    return String.valueOf(message.get("content"));
} catch (RuntimeException ex) {
    metrics.aiCallFinished(sample, "failure");
    throw ex;
}
```

Update tests to pass `new AnalysisMetrics(new SimpleMeterRegistry())`.

- [x] **Step 6: Run focused tests**

```powershell
mvn "-Dtest=AnalysisOutboxPublisherTest,RedisReportCacheTest,OpenAiCompatibleClientTest,OutboxMetricsTest" test
```

Expected: PASS.

- [x] **Step 7: Commit**

```powershell
git add src/main/java/com/zhulikang/aimatch/observability/OutboxMetrics.java src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxRepository.java src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxPublisher.java src/main/java/com/zhulikang/aimatch/analysis/RedisReportCache.java src/main/java/com/zhulikang/aimatch/ai/OpenAiCompatibleClient.java src/test/java/com/zhulikang/aimatch/analysis/AnalysisOutboxPublisherTest.java src/test/java/com/zhulikang/aimatch/analysis/RedisReportCacheTest.java src/test/java/com/zhulikang/aimatch/ai/OpenAiCompatibleClientTest.java src/test/java/com/zhulikang/aimatch/observability/OutboxMetricsTest.java
git commit -m "feat: add ai cache and outbox metrics"
```

---

## Task 5: Actuator Metrics Endpoint and Operations Docs

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/com/zhulikang/aimatch/config/DeploymentConfigurationTest.java`
- Modify: `README.md`
- Modify: `docs/operations/runbook.md`
- Modify: `docs/development.md`
- Modify: `docs/architecture.md`

- [x] **Step 1: Write failing config/docs test**

In `DeploymentConfigurationTest.commonConfigurationUsesDevAsDefaultProfileAndExposesHealthProbes`, add:

```java
assertThat(properties.getProperty("management.endpoints.web.exposure.include")).contains("metrics");
```

Add assertions that docs mention current metrics:

```java
@Test
void docsDescribeObservabilityMetricsAndCorrelationIds() throws IOException {
    String readme = read("README.md");
    String runbook = read("docs/operations/runbook.md");
    String architecture = read("docs/architecture.md");

    assertThat(readme).contains("X-Request-Id").contains("/actuator/metrics");
    assertThat(runbook).contains("analysis.tasks.created").contains("analysis.outbox.backlog");
    assertThat(architecture).contains("requestId").contains("correlationId").contains("Micrometer");
}
```

- [x] **Step 2: Run test and verify RED**

```powershell
mvn "-Dtest=DeploymentConfigurationTest" test
```

Expected: FAIL because metrics endpoint/docs are not updated.

- [x] **Step 3: Expose Actuator metrics**

Change `application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

- [x] **Step 4: Update README**

Add to README after health check:

```markdown
请求链路会返回 `X-Request-Id` 和 `X-Correlation-Id`。客户端可以传入这两个 header；缺失或非法时服务会生成安全 UUID。错误响应包含 `requestId`，便于在日志中定位同一次请求。

常用指标入口：

```powershell
curl.exe -i http://localhost:8080/actuator/metrics
curl.exe -i http://localhost:8080/actuator/metrics/analysis.tasks.created
curl.exe -i http://localhost:8080/actuator/metrics/analysis.outbox.backlog
```
```

- [x] **Step 5: Update runbook**

Replace Phase 5 placeholder bullets with concrete sections:

```markdown
## 8. 可观测性检查

请求定位：

```powershell
curl.exe -i `
  -H "X-API-Token: dev-token" `
  -H "X-Request-Id: local-debug-1" `
  -H "X-Correlation-Id: local-correlation-1" `
  http://localhost:8080/api/analysis/1
```

日志中可按 `requestId`、`correlationId`、`taskId` 和 `event=analysis_task_*` 搜索任务生命周期。日志不得包含简历原文、JD 原文、prompt 或 AI 原始响应。

指标检查：

```powershell
curl.exe -i http://localhost:8080/actuator/metrics/analysis.tasks.created
curl.exe -i http://localhost:8080/actuator/metrics/analysis.tasks.succeeded
curl.exe -i http://localhost:8080/actuator/metrics/analysis.tasks.failed
curl.exe -i http://localhost:8080/actuator/metrics/analysis.worker.duration
curl.exe -i http://localhost:8080/actuator/metrics/ai.calls
curl.exe -i http://localhost:8080/actuator/metrics/report.cache.requests
curl.exe -i http://localhost:8080/actuator/metrics/analysis.outbox.events
curl.exe -i http://localhost:8080/actuator/metrics/analysis.outbox.backlog
```

RabbitMQ 队列深度仍通过 RabbitMQ 管理页或 `docker compose exec rabbitmq rabbitmqctl list_queues` 检查。
```

- [x] **Step 6: Update development and architecture docs**

In `docs/development.md`, mark Phase 5 current behavior as completed after implementation and Phase 6 as next.

In `docs/architecture.md`, add:

```markdown
## 4.7 可观测性

HTTP 请求经过 `RequestCorrelationFilter`，生成或复用 `X-Request-Id` 和 `X-Correlation-Id`，并写入 SLF4J MDC。API 错误响应包含 `requestId`。创建分析任务时，当前 correlation ID 写入 outbox payload；outbox publisher 将它发布为 RabbitMQ header；worker 消费时恢复到 MDC。

任务生命周期日志使用 key-value 风格字段：`event`、`requestId`、`correlationId`、`taskId`、`resumeId`、`jobDescriptionId`、`attempt`、`failureCode`。日志只记录 ID、状态和失败分类，不记录简历原文、JD 原文、prompt 或 AI 响应正文。

Micrometer 指标覆盖 `analysis.tasks.created`、`analysis.tasks.succeeded`、`analysis.tasks.failed`、`analysis.worker.duration`、`ai.calls`、`ai.call.duration`、`report.cache.requests`、`report.cache.writes`、`analysis.outbox.events` 和 `analysis.outbox.backlog`。
```

- [x] **Step 7: Run focused tests and stale wording scan**

```powershell
mvn "-Dtest=DeploymentConfigurationTest" test
rg -n "Phase 5 需要继续补充|request ID 和 correlation ID。|task、AI、cache、outbox metrics。" README.md docs --glob "!docs/superpowers/plans/2026-07-06-phase-5-observability-operations.md"
git diff --check
```

Expected: test passes, stale wording scan has no matches, no whitespace errors.

- [ ] **Step 8: Commit**

```powershell
git add src/main/resources/application.yml src/test/java/com/zhulikang/aimatch/config/DeploymentConfigurationTest.java README.md docs/operations/runbook.md docs/development.md docs/architecture.md
git commit -m "docs: document observability operations"
```

---

## Task 6: Final Verification and Review

**Files:**
- No code files unless verification or review reveals a bug.

- [ ] **Step 1: Run full fast tests**

```powershell
mvn test
```

Expected: PASS.

- [ ] **Step 2: Run Docker-backed integration verification**

```powershell
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'; mvn verify
```

Expected: PASS.

- [ ] **Step 3: Validate compose config and whitespace**

```powershell
docker compose --env-file .env.example config --quiet
git diff --check
```

Expected: PASS/no output except line-ending warnings.

- [ ] **Step 4: Request read-only review**

Request a read-only review focused on:

- Error responses always include request ID without breaking existing response codes.
- Correlation ID is not logged as raw sensitive content and is safely bounded.
- RabbitMQ correlation headers do not break publisher confirm/return behavior.
- Metrics do not create duplicate meters with unbounded tags.
- Structured task logs do not contain resume/JD/prompt/AI response content.
- Docs match actual metric names and endpoints.

- [ ] **Step 5: Commit fixes if needed**

If review or verification reveals issues, fix them with focused commits and rerun the relevant focused tests plus `mvn test`.

---

## Acceptance Checklist

- `mvn test` passes.
- `mvn verify` passes when Docker/Testcontainers is available.
- API error responses include `requestId`.
- HTTP responses include `X-Request-Id` and `X-Correlation-Id`.
- Incoming safe request/correlation headers are reused.
- Outbox payload contains correlation ID and RabbitMQ publish sends it as a header.
- Worker restores correlation ID into MDC while processing a message and clears it after processing.
- Task lifecycle logs include structured fields and do not include raw resume, JD, prompt, or AI output.
- Micrometer metrics exist for analysis tasks, worker duration, AI calls, Redis cache, outbox events, and outbox backlog.
- Actuator exposes `metrics`.
- README, architecture docs, development guide, and runbook describe current observability behavior.

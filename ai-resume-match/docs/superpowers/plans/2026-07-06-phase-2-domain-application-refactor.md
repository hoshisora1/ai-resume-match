# Phase 2 Domain and Application Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce application use cases, explicit analysis-task state transitions, failure metadata, retry flow, and a thin RabbitMQ worker while preserving the existing public API workflow.

**Architecture:** Keep the Spring Boot monolith and existing JPA entity packages for compatibility in this phase. Add `application.*` use-case classes around the current repositories and services, move worker orchestration into `RunAnalysisUseCase`, and enrich `AnalysisTask` with attempts, failure codes, and timestamps. Do not introduce Flyway, outbox, Docker changes, Actuator, Testcontainers, frontend, accounts, RBAC, billing, multi-tenancy, or external vector databases in this phase.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring MVC, Spring Data JPA, RabbitMQ via `RabbitTemplate`, JUnit 5, Mockito, AssertJ, MockMvc, H2 for fast tests.

---

## Scope

Covered in Phase 2:

- Use-case classes for resume upload, JD creation, analysis task creation, task lookup, report lookup, retry, and run-analysis orchestration.
- Controller delegates to use cases instead of directly touching repositories and document/JD helpers.
- `AnalysisTask` gains explicit transition methods, expanded statuses, attempts, failure code/message, and lifecycle timestamps.
- `AnalysisTaskResponse` exposes attempts and failure metadata.
- `POST /api/analysis/{taskId}/retry` retries only `FAILED_RETRYABLE` tasks and republishes the task id through the existing RabbitMQ publisher path.
- `AnalysisWorker` becomes a thin listener that delegates to `RunAnalysisUseCase`.
- Fast unit/slice tests remain under `mvn test`.

Deferred:

- Flyway and schema migrations.
- Outbox publisher and reliable message delivery.
- Broker-level integration tests and Testcontainers.
- Actuator, request ID, correlation ID, metrics, and structured logs.
- Dockerfile and compose app service.

## File Structure

- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisTask.java`
  - Add expanded status enum, attempts/failure/timestamp fields, transition methods, and getters.
- Create: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisFailureCode.java`
  - Stable failure-code enum used by task state and responses.
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisTaskRepository.java`
  - Update atomic status queries for attempts, failure metadata, and retry transitions.
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisTaskService.java`
  - Use richer repository updates for start/success/failure/retry.
- Create: `src/main/java/com/zhulikang/aimatch/application/analysis/AnalysisTaskPublisher.java`
  - Publishes task ids after commit through the existing exchange/routing key.
- Create: `src/main/java/com/zhulikang/aimatch/application/resume/UploadResumeUseCase.java`
- Create: `src/main/java/com/zhulikang/aimatch/application/job/CreateJobDescriptionUseCase.java`
- Create: `src/main/java/com/zhulikang/aimatch/application/analysis/CreateAnalysisTaskUseCase.java`
- Create: `src/main/java/com/zhulikang/aimatch/application/analysis/GetAnalysisTaskUseCase.java`
- Create: `src/main/java/com/zhulikang/aimatch/application/analysis/RetryAnalysisTaskUseCase.java`
- Create: `src/main/java/com/zhulikang/aimatch/application/analysis/RunAnalysisUseCase.java`
- Create: `src/main/java/com/zhulikang/aimatch/application/report/GetMatchReportUseCase.java`
- Modify: `src/main/java/com/zhulikang/aimatch/api/ResumeMatchController.java`
  - Delegate to use cases and add retry endpoint.
- Modify: `src/main/java/com/zhulikang/aimatch/api/AnalysisTaskResponse.java`
  - Add attempts and failure fields while preserving existing fields.
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisWorker.java`
  - Delegate to `RunAnalysisUseCase`.
- Modify tests and add focused tests listed in each task.

---

### Task 1: Analysis Task State Model

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisFailureCode.java`
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisTask.java`
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisTaskRepository.java`
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisTaskService.java`
- Modify: `src/main/java/com/zhulikang/aimatch/api/AnalysisTaskResponse.java`
- Create: `src/test/java/com/zhulikang/aimatch/analysis/AnalysisTaskTest.java`
- Modify: `src/test/java/com/zhulikang/aimatch/analysis/AnalysisTaskServiceTest.java`
- Modify: `src/test/java/com/zhulikang/aimatch/api/ResumeMatchControllerTest.java`
- Modify: `src/test/java/com/zhulikang/aimatch/analysis/DomainRepositoryTest.java`

- [ ] **Step 1: Write failing task transition tests**

Create `src/test/java/com/zhulikang/aimatch/analysis/AnalysisTaskTest.java`:

```java
package com.zhulikang.aimatch.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisTaskTest {
    @Test
    void startsPendingTaskAndIncrementsAttempt() {
        AnalysisTask task = new AnalysisTask(1L, 2L);

        task.markRunning();

        assertThat(task.getStatus()).isEqualTo(AnalysisTask.Status.RUNNING);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getStartedAt()).isNotNull();
        assertThat(task.getUpdatedAt()).isNotNull();
    }

    @Test
    void marksRetryableFailureWithMetadata() {
        AnalysisTask task = new AnalysisTask(1L, 2L);
        task.markRunning();

        task.markRetryableFailure(AnalysisFailureCode.AI_UNAVAILABLE, "AI unavailable");

        assertThat(task.getStatus()).isEqualTo(AnalysisTask.Status.FAILED_RETRYABLE);
        assertThat(task.getFailureCode()).isEqualTo(AnalysisFailureCode.AI_UNAVAILABLE);
        assertThat(task.getFailureMessage()).isEqualTo("AI unavailable");
        assertThat(task.getCompletedAt()).isNotNull();
    }

    @Test
    void retriesOnlyRetryableFailures() {
        AnalysisTask task = new AnalysisTask(1L, 2L);
        task.markRunning();
        task.markRetryableFailure(AnalysisFailureCode.AI_UNAVAILABLE, "AI unavailable");

        task.retry();

        assertThat(task.getStatus()).isEqualTo(AnalysisTask.Status.PENDING);
        assertThat(task.getFailureCode()).isNull();
        assertThat(task.getFailureMessage()).isNull();
        assertThat(task.getCompletedAt()).isNull();
    }

    @Test
    void rejectsRetryForFinalFailure() {
        AnalysisTask task = new AnalysisTask(1L, 2L);
        task.markRunning();
        task.markFinalFailure(AnalysisFailureCode.SOURCE_DATA_MISSING, "Missing source data");

        assertThatThrownBy(task::retry)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Only retryable failed analysis tasks can be retried");
    }
}
```

- [ ] **Step 2: Run transition test and verify it fails**

Run:

```powershell
mvn "-Dtest=AnalysisTaskTest" test
```

Expected: FAIL because `AnalysisFailureCode`, expanded statuses, attempts, and transition methods do not exist.

- [ ] **Step 3: Implement failure code enum**

Create `src/main/java/com/zhulikang/aimatch/analysis/AnalysisFailureCode.java`:

```java
package com.zhulikang.aimatch.analysis;

public enum AnalysisFailureCode {
    AI_UNAVAILABLE,
    REPORT_PARSE_FAILED,
    SOURCE_DATA_MISSING,
    UNEXPECTED_ERROR
}
```

- [ ] **Step 4: Expand `AnalysisTask`**

Update `src/main/java/com/zhulikang/aimatch/analysis/AnalysisTask.java` so the class contains these fields, enum values, transition methods, and getters:

```java
public enum Status {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    CANCELLED
}

@Column(nullable = false)
private int attemptCount;

@Column(nullable = false)
private int maxAttempts = 3;

@Enumerated(EnumType.STRING)
private AnalysisFailureCode failureCode;

private String failureMessage;

private LocalDateTime nextRetryAt;

private LocalDateTime startedAt;

private LocalDateTime completedAt;
```

Replace the old transition methods with:

```java
public void markRunning() {
    if (status != Status.PENDING && status != Status.RUNNING) {
        throw new IllegalStateException("Only pending or stale running analysis tasks can be started");
    }
    this.status = Status.RUNNING;
    this.attemptCount++;
    this.failureCode = null;
    this.failureMessage = null;
    this.nextRetryAt = null;
    this.startedAt = LocalDateTime.now();
    this.completedAt = null;
    this.updatedAt = LocalDateTime.now();
}

public void markSuccess() {
    this.status = Status.SUCCESS;
    this.failureCode = null;
    this.failureMessage = null;
    this.nextRetryAt = null;
    this.completedAt = LocalDateTime.now();
    this.updatedAt = LocalDateTime.now();
}

public void markRetryableFailure(AnalysisFailureCode failureCode, String failureMessage) {
    this.status = Status.FAILED_RETRYABLE;
    this.failureCode = failureCode;
    this.failureMessage = failureMessage;
    this.completedAt = LocalDateTime.now();
    this.updatedAt = LocalDateTime.now();
}

public void markFinalFailure(AnalysisFailureCode failureCode, String failureMessage) {
    this.status = Status.FAILED_FINAL;
    this.failureCode = failureCode;
    this.failureMessage = failureMessage;
    this.nextRetryAt = null;
    this.completedAt = LocalDateTime.now();
    this.updatedAt = LocalDateTime.now();
}

public void retry() {
    if (status != Status.FAILED_RETRYABLE) {
        throw new IllegalStateException("Only retryable failed analysis tasks can be retried");
    }
    this.status = Status.PENDING;
    this.failureCode = null;
    this.failureMessage = null;
    this.nextRetryAt = null;
    this.completedAt = null;
    this.updatedAt = LocalDateTime.now();
}
```

Add getters for `attemptCount`, `maxAttempts`, `failureCode`, `failureMessage`, `nextRetryAt`, `startedAt`, and `completedAt`.

- [ ] **Step 5: Update repository queries**

In `AnalysisTaskRepository`, replace `markRunningIfPendingOrStale` query with one that increments attempts and clears old failure data:

```java
@Modifying
@Query("""
    update AnalysisTask t
    set t.status = :running,
        t.attemptCount = t.attemptCount + 1,
        t.failureCode = null,
        t.failureMessage = null,
        t.nextRetryAt = null,
        t.startedAt = :now,
        t.completedAt = null,
        t.updatedAt = :now
    where t.id = :taskId
      and (
        t.status = :pending
        or (t.status = :running and (:redelivered = true or t.updatedAt < :staleBefore))
      )
    """)
int markRunningIfPendingOrStale(
    @Param("taskId") Long taskId,
    @Param("running") AnalysisTask.Status running,
    @Param("pending") AnalysisTask.Status pending,
    @Param("staleBefore") LocalDateTime staleBefore,
    @Param("now") LocalDateTime now,
    @Param("redelivered") boolean redelivered
);
```

Add these repository methods:

```java
@Modifying
@Query("""
    update AnalysisTask t
    set t.status = :status,
        t.failureCode = null,
        t.failureMessage = null,
        t.nextRetryAt = null,
        t.completedAt = :now,
        t.updatedAt = :now
    where t.id = :taskId
    """)
int markSuccess(@Param("taskId") Long taskId, @Param("status") AnalysisTask.Status status, @Param("now") LocalDateTime now);

@Modifying
@Query("""
    update AnalysisTask t
    set t.status = :status,
        t.failureCode = :failureCode,
        t.failureMessage = :failureMessage,
        t.completedAt = :now,
        t.updatedAt = :now
    where t.id = :taskId
    """)
int markFailure(
    @Param("taskId") Long taskId,
    @Param("status") AnalysisTask.Status status,
    @Param("failureCode") AnalysisFailureCode failureCode,
    @Param("failureMessage") String failureMessage,
    @Param("now") LocalDateTime now
);
```

- [ ] **Step 6: Update task service tests**

Extend `AnalysisTaskServiceTest` with:

```java
@Test
void marksRetryableFailureWithFailureCodeAndMessage() {
    AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    MatchReportRepository reportRepository = mock(MatchReportRepository.class);
    AnalysisTaskService service = new AnalysisTaskService(taskRepository, reportRepository, Duration.ofMinutes(15));

    service.markRetryableFailure(99L, AnalysisFailureCode.AI_UNAVAILABLE, "AI unavailable");

    verify(taskRepository).markFailure(
        eq(99L),
        eq(AnalysisTask.Status.FAILED_RETRYABLE),
        eq(AnalysisFailureCode.AI_UNAVAILABLE),
        eq("AI unavailable"),
        any()
    );
}
```

Update the success test to verify `markSuccess(99L, AnalysisTask.Status.SUCCESS, any())` instead of the old status update method.

- [ ] **Step 7: Update task response**

Update `AnalysisTaskResponse` to include:

```java
int attemptCount,
int maxAttempts,
String failureCode,
String failureMessage,
LocalDateTime nextRetryAt,
LocalDateTime startedAt,
LocalDateTime completedAt
```

In `from(AnalysisTask task)`, map `failureCode` with:

```java
task.getFailureCode() == null ? null : task.getFailureCode().name()
```

- [ ] **Step 8: Update controller status test**

In `ResumeMatchControllerTest.returnsAnalysisTaskStatus`, add assertions:

```java
.andExpect(jsonPath("$.attemptCount").value(0))
.andExpect(jsonPath("$.maxAttempts").value(3))
.andExpect(jsonPath("$.failureCode").doesNotExist())
.andExpect(jsonPath("$.failureMessage").doesNotExist())
```

- [ ] **Step 9: Run focused tests**

Run:

```powershell
mvn "-Dtest=AnalysisTaskTest,AnalysisTaskServiceTest,ResumeMatchControllerTest,DomainRepositoryTest" test
```

Expected: PASS.

- [ ] **Step 10: Commit**

```powershell
git add src/main/java/com/zhulikang/aimatch/analysis/AnalysisFailureCode.java src/main/java/com/zhulikang/aimatch/analysis/AnalysisTask.java src/main/java/com/zhulikang/aimatch/analysis/AnalysisTaskRepository.java src/main/java/com/zhulikang/aimatch/analysis/AnalysisTaskService.java src/main/java/com/zhulikang/aimatch/api/AnalysisTaskResponse.java src/test/java/com/zhulikang/aimatch/analysis/AnalysisTaskTest.java src/test/java/com/zhulikang/aimatch/analysis/AnalysisTaskServiceTest.java src/test/java/com/zhulikang/aimatch/api/ResumeMatchControllerTest.java src/test/java/com/zhulikang/aimatch/analysis/DomainRepositoryTest.java
git commit -m "feat: enrich analysis task state"
```

---

### Task 2: Application Use Cases and Thin Controller

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/application/analysis/AnalysisTaskPublisher.java`
- Create: `src/main/java/com/zhulikang/aimatch/application/resume/UploadResumeUseCase.java`
- Create: `src/main/java/com/zhulikang/aimatch/application/job/CreateJobDescriptionUseCase.java`
- Create: `src/main/java/com/zhulikang/aimatch/application/analysis/CreateAnalysisTaskUseCase.java`
- Create: `src/main/java/com/zhulikang/aimatch/application/analysis/GetAnalysisTaskUseCase.java`
- Create: `src/main/java/com/zhulikang/aimatch/application/report/GetMatchReportUseCase.java`
- Modify: `src/main/java/com/zhulikang/aimatch/api/ResumeMatchController.java`
- Create: `src/test/java/com/zhulikang/aimatch/application/resume/UploadResumeUseCaseTest.java`
- Create: `src/test/java/com/zhulikang/aimatch/application/job/CreateJobDescriptionUseCaseTest.java`
- Create: `src/test/java/com/zhulikang/aimatch/application/analysis/CreateAnalysisTaskUseCaseTest.java`
- Create: `src/test/java/com/zhulikang/aimatch/application/report/GetMatchReportUseCaseTest.java`
- Modify: `src/test/java/com/zhulikang/aimatch/api/ResumeMatchControllerTest.java`

- [ ] **Step 1: Write failing use-case tests**

Create `UploadResumeUseCaseTest`:

```java
package com.zhulikang.aimatch.application.resume;

import com.zhulikang.aimatch.document.DocumentTextExtractor;
import com.zhulikang.aimatch.document.ResumeFileValidator;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadResumeUseCaseTest {
    @Test
    void validatesBeforeExtractingAndSavingResume() {
        ResumeFileValidator validator = mock(ResumeFileValidator.class);
        DocumentTextExtractor extractor = mock(DocumentTextExtractor.class);
        ResumeRepository repository = mock(ResumeRepository.class);
        UploadResumeUseCase useCase = new UploadResumeUseCase(validator, extractor, repository);
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[] {1});
        Resume saved = new Resume("resume.pdf", "Java Redis", "Java Redis");
        when(extractor.extract(file)).thenReturn("Java Redis");
        when(repository.save(any(Resume.class))).thenReturn(saved);

        assertThat(useCase.upload(file)).isSameAs(saved);
        verify(validator).validate(file);
        verify(repository).save(any(Resume.class));
    }

    @Test
    void rejectsBlankExtractedText() {
        ResumeFileValidator validator = mock(ResumeFileValidator.class);
        DocumentTextExtractor extractor = mock(DocumentTextExtractor.class);
        ResumeRepository repository = mock(ResumeRepository.class);
        UploadResumeUseCase useCase = new UploadResumeUseCase(validator, extractor, repository);
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[] {1});
        when(extractor.extract(file)).thenReturn("   ");

        assertThatThrownBy(() -> useCase.upload(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Resume text must not be blank");
    }
}
```

Create focused tests for `CreateJobDescriptionUseCase`, `CreateAnalysisTaskUseCase`, and `GetMatchReportUseCase`:

`CreateJobDescriptionUseCaseTest` must instantiate mocked `JdTagExtractor` and `JobDescriptionRepository`, call `create("Java Redis")`, assert the returned job is the saved instance, and verify `extractTags("Java Redis")`, `toStorageValue(List.of("Java", "Redis"))`, and `jobRepository.save(any(JobDescription.class))`.

`CreateAnalysisTaskUseCaseTest` must cover three cases:

```java
assertThatThrownBy(() -> useCase.create(1L, 2L))
    .isInstanceOf(ResourceNotFoundException.class)
    .hasMessage("Resume not found");
```

```java
assertThatThrownBy(() -> useCase.create(1L, 2L))
    .isInstanceOf(ResourceNotFoundException.class)
    .hasMessage("Job description not found");
```

```java
AnalysisTask task = useCase.create(1L, 2L);
assertThat(task.getResumeId()).isEqualTo(1L);
assertThat(task.getJobDescriptionId()).isEqualTo(2L);
verify(publisher).publishAfterCommit(99L);
```

`GetMatchReportUseCaseTest` must cover cache hit and cache miss:

```java
when(reportCache.get(99L)).thenReturn(Optional.of(cached));
assertThat(useCase.find(99L)).contains(cached);
verifyNoInteractions(reportRepository);
```

```java
when(reportCache.get(99L)).thenReturn(Optional.empty());
when(reportRepository.findByTaskId(99L)).thenReturn(Optional.of(report));
assertThat(useCase.find(99L)).contains(MatchReportView.from(report));
verify(reportCache).put(MatchReportView.from(report));
```

- [ ] **Step 2: Run use-case tests and verify they fail**

Run:

```powershell
mvn "-Dtest=UploadResumeUseCaseTest,CreateJobDescriptionUseCaseTest,CreateAnalysisTaskUseCaseTest,GetMatchReportUseCaseTest" test
```

Expected: FAIL because use-case classes do not exist.

- [ ] **Step 3: Implement `AnalysisTaskPublisher`**

Create `src/main/java/com/zhulikang/aimatch/application/analysis/AnalysisTaskPublisher.java`:

```java
package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.RabbitConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class AnalysisTaskPublisher {
    private final RabbitTemplate rabbitTemplate;

    public AnalysisTaskPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishAfterCommit(Long taskId) {
        Runnable publish = () -> rabbitTemplate.convertAndSend(
            RabbitConfig.ANALYSIS_EXCHANGE,
            RabbitConfig.ANALYSIS_ROUTING_KEY,
            taskId
        );
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish.run();
            }
        });
    }
}
```

- [ ] **Step 4: Implement resume and job use cases**

Create `UploadResumeUseCase`:

```java
package com.zhulikang.aimatch.application.resume;

import com.zhulikang.aimatch.document.DocumentTextExtractor;
import com.zhulikang.aimatch.document.ResumeFileValidator;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class UploadResumeUseCase {
    private final ResumeFileValidator resumeFileValidator;
    private final DocumentTextExtractor extractor;
    private final ResumeRepository resumeRepository;

    public UploadResumeUseCase(ResumeFileValidator resumeFileValidator, DocumentTextExtractor extractor, ResumeRepository resumeRepository) {
        this.resumeFileValidator = resumeFileValidator;
        this.extractor = extractor;
        this.resumeRepository = resumeRepository;
    }

    public Resume upload(MultipartFile file) {
        resumeFileValidator.validate(file);
        String rawText = extractor.extract(file);
        if (rawText.isBlank()) {
            throw new IllegalArgumentException("Resume text must not be blank");
        }
        return resumeRepository.save(new Resume(file.getOriginalFilename(), rawText, rawText));
    }
}
```

Create `CreateJobDescriptionUseCase`:

```java
package com.zhulikang.aimatch.application.job;

import com.zhulikang.aimatch.job.JdTagExtractor;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import org.springframework.stereotype.Service;

@Service
public class CreateJobDescriptionUseCase {
    private final JdTagExtractor jdTagExtractor;
    private final JobDescriptionRepository jobRepository;

    public CreateJobDescriptionUseCase(JdTagExtractor jdTagExtractor, JobDescriptionRepository jobRepository) {
        this.jdTagExtractor = jdTagExtractor;
        this.jobRepository = jobRepository;
    }

    public JobDescription create(String content) {
        String tags = jdTagExtractor.toStorageValue(jdTagExtractor.extractTags(content));
        return jobRepository.save(new JobDescription(content, tags));
    }
}
```

- [ ] **Step 5: Implement analysis and report use cases**

Create `CreateAnalysisTaskUseCase`, `GetAnalysisTaskUseCase`, and `GetMatchReportUseCase` with the same behavior currently held by `AnalysisService`, but with resume/job existence checks in the create use case.

`CreateAnalysisTaskUseCase.create(Long resumeId, Long jobDescriptionId)` must:

1. Throw `new ResourceNotFoundException("Resume not found")` if resume does not exist.
2. Throw `new ResourceNotFoundException("Job description not found")` if job does not exist.
3. Save `new AnalysisTask(resumeId, jobDescriptionId)`.
4. Call `publisher.publishAfterCommit(task.getId())`.
5. Return the saved task.

- [ ] **Step 6: Refactor controller to use use cases**

Change `ResumeMatchController` constructor dependencies to:

```java
UploadResumeUseCase uploadResumeUseCase,
CreateJobDescriptionUseCase createJobDescriptionUseCase,
CreateAnalysisTaskUseCase createAnalysisTaskUseCase,
GetAnalysisTaskUseCase getAnalysisTaskUseCase,
GetMatchReportUseCase getMatchReportUseCase
```

Controller methods should delegate:

```java
@PostMapping("/resumes")
public ResumeUploadResponse uploadResume(@RequestParam("file") MultipartFile file) {
    Resume resume = uploadResumeUseCase.upload(file);
    return new ResumeUploadResponse(resume.getId());
}

@PostMapping("/jobs")
public JobDescriptionResponse createJob(@Valid @RequestBody CreateJobRequest request) {
    JobDescription job = createJobDescriptionUseCase.create(request.content());
    return new JobDescriptionResponse(job.getId());
}

@PostMapping("/analysis")
public AnalysisTaskResponse createAnalysis(@Valid @RequestBody CreateAnalysisRequest request) {
    return AnalysisTaskResponse.from(createAnalysisTaskUseCase.create(request.resumeId(), request.jobDescriptionId()));
}

@GetMapping("/analysis/{taskId}")
public ResponseEntity<AnalysisTaskResponse> analysisTask(@PathVariable Long taskId) {
    return getAnalysisTaskUseCase.find(taskId)
        .map(AnalysisTaskResponse::from)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
}

@GetMapping("/analysis/{taskId}/report")
public ResponseEntity<MatchReportView> report(@PathVariable Long taskId) {
    return getMatchReportUseCase.find(taskId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
}
```

- [ ] **Step 7: Update controller tests**

In `ResumeMatchControllerTest`, replace repository/helper mocks with use-case mocks. Keep the existing HTTP response assertions unchanged.

- [ ] **Step 8: Run focused tests**

Run:

```powershell
mvn "-Dtest=ResumeMatchControllerTest,UploadResumeUseCaseTest,CreateJobDescriptionUseCaseTest,CreateAnalysisTaskUseCaseTest,GetMatchReportUseCaseTest" test
```

Expected: PASS.

- [ ] **Step 9: Commit**

```powershell
git add src/main/java/com/zhulikang/aimatch/application src/main/java/com/zhulikang/aimatch/api/ResumeMatchController.java src/test/java/com/zhulikang/aimatch/application src/test/java/com/zhulikang/aimatch/api/ResumeMatchControllerTest.java
git commit -m "refactor: introduce application use cases"
```

---

### Task 3: Retry Analysis Task Use Case and Endpoint

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/application/analysis/RetryAnalysisTaskUseCase.java`
- Modify: `src/main/java/com/zhulikang/aimatch/api/ResumeMatchController.java`
- Modify: `src/test/java/com/zhulikang/aimatch/api/ResumeMatchControllerTest.java`
- Create: `src/test/java/com/zhulikang/aimatch/application/analysis/RetryAnalysisTaskUseCaseTest.java`

- [ ] **Step 1: Write failing retry use-case tests**

Create `RetryAnalysisTaskUseCaseTest`:

```java
package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisFailureCode;
import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetryAnalysisTaskUseCaseTest {
    @Test
    void retriesRetryableFailedTaskAndPublishesIt() {
        AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
        AnalysisTaskPublisher publisher = mock(AnalysisTaskPublisher.class);
        RetryAnalysisTaskUseCase useCase = new RetryAnalysisTaskUseCase(repository, publisher);
        AnalysisTask task = new AnalysisTask(1L, 2L);
        ReflectionTestUtils.setField(task, "id", 99L);
        task.markRunning();
        task.markRetryableFailure(AnalysisFailureCode.AI_UNAVAILABLE, "AI unavailable");
        when(repository.findById(99L)).thenReturn(Optional.of(task));
        when(repository.save(task)).thenReturn(task);

        AnalysisTask retried = useCase.retry(99L);

        assertThat(retried.getStatus()).isEqualTo(AnalysisTask.Status.PENDING);
        verify(repository).save(task);
        verify(publisher).publishAfterCommit(99L);
    }

    @Test
    void rejectsMissingTask() {
        AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
        RetryAnalysisTaskUseCase useCase = new RetryAnalysisTaskUseCase(repository, mock(AnalysisTaskPublisher.class));
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.retry(404L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("Analysis task not found");
    }
}
```

- [ ] **Step 2: Add failing controller retry tests**

Add to `ResumeMatchControllerTest`:

```java
@Test
void retriesAnalysisTask() throws Exception {
    AnalysisTask task = new AnalysisTask(10L, 20L);
    ReflectionTestUtils.setField(task, "id", 30L);
    when(retryAnalysisTaskUseCase.retry(30L)).thenReturn(task);

    mockMvc.perform(post("/api/analysis/30/retry").header("X-API-Token", "test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.taskId").value(30))
        .andExpect(jsonPath("$.status").value("PENDING"));
}
```

- [ ] **Step 3: Run focused retry tests and verify they fail**

Run:

```powershell
mvn "-Dtest=RetryAnalysisTaskUseCaseTest,ResumeMatchControllerTest" test
```

Expected: FAIL because retry use case and endpoint do not exist.

- [ ] **Step 4: Implement retry use case**

Create `RetryAnalysisTaskUseCase`:

```java
package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.api.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetryAnalysisTaskUseCase {
    private final AnalysisTaskRepository taskRepository;
    private final AnalysisTaskPublisher publisher;

    public RetryAnalysisTaskUseCase(AnalysisTaskRepository taskRepository, AnalysisTaskPublisher publisher) {
        this.taskRepository = taskRepository;
        this.publisher = publisher;
    }

    @Transactional
    public AnalysisTask retry(Long taskId) {
        AnalysisTask task = taskRepository.findById(taskId)
            .orElseThrow(() -> new ResourceNotFoundException("Analysis task not found"));
        task.retry();
        AnalysisTask saved = taskRepository.save(task);
        publisher.publishAfterCommit(saved.getId());
        return saved;
    }
}
```

- [ ] **Step 5: Add retry endpoint**

Inject `RetryAnalysisTaskUseCase` into `ResumeMatchController` and add:

```java
@PostMapping("/analysis/{taskId}/retry")
public AnalysisTaskResponse retryAnalysis(@PathVariable Long taskId) {
    return AnalysisTaskResponse.from(retryAnalysisTaskUseCase.retry(taskId));
}
```

- [ ] **Step 6: Run focused tests**

Run:

```powershell
mvn "-Dtest=RetryAnalysisTaskUseCaseTest,ResumeMatchControllerTest" test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/zhulikang/aimatch/application/analysis/RetryAnalysisTaskUseCase.java src/main/java/com/zhulikang/aimatch/api/ResumeMatchController.java src/test/java/com/zhulikang/aimatch/application/analysis/RetryAnalysisTaskUseCaseTest.java src/test/java/com/zhulikang/aimatch/api/ResumeMatchControllerTest.java
git commit -m "feat: add analysis retry use case"
```

---

### Task 4: Run Analysis Use Case and Thin Worker

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/application/analysis/RunAnalysisUseCase.java`
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisWorker.java`
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisTaskService.java`
- Create: `src/test/java/com/zhulikang/aimatch/application/analysis/RunAnalysisUseCaseTest.java`
- Modify: `src/test/java/com/zhulikang/aimatch/analysis/AnalysisWorkerTest.java`

- [ ] **Step 1: Write failing run-analysis use-case tests**

Create `RunAnalysisUseCaseTest` by moving the current orchestration expectations out of `AnalysisWorkerTest`. It must cover:

- Successful run creates `MatchReport` and calls `AnalysisTaskService.completeSuccess`.
- AI failure calls `AnalysisTaskService.markRetryableFailure(taskId, AnalysisFailureCode.AI_UNAVAILABLE, "AI unavailable")`.
- Missing source data calls `markFinalFailure(taskId, AnalysisFailureCode.SOURCE_DATA_MISSING, "Analysis source data is missing")`.
- Cannot start skips repository and AI calls.
- Redelivered message passes `redelivered=true` to `tryStart`.

- [ ] **Step 2: Write failing thin-worker test**

Replace `AnalysisWorkerTest` with a focused test:

```java
package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.application.analysis.RunAnalysisUseCase;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AnalysisWorkerTest {
    @Test
    void delegatesMessageToRunAnalysisUseCase() {
        RunAnalysisUseCase useCase = mock(RunAnalysisUseCase.class);
        AnalysisWorker worker = new AnalysisWorker(useCase);

        worker.handle(99L, true);

        verify(useCase).run(99L, true);
    }
}
```

- [ ] **Step 3: Run focused tests and verify they fail**

Run:

```powershell
mvn "-Dtest=RunAnalysisUseCaseTest,AnalysisWorkerTest" test
```

Expected: FAIL because `RunAnalysisUseCase` does not exist and worker still owns orchestration.

- [ ] **Step 4: Implement `RunAnalysisUseCase`**

Create `RunAnalysisUseCase` using the dependencies currently held by `AnalysisWorker`:

```java
@Service
public class RunAnalysisUseCase {
    private static final Logger log = LoggerFactory.getLogger(RunAnalysisUseCase.class);

    public void run(Long taskId, boolean redelivered) {
        if (!taskService.tryStart(taskId, redelivered)) {
            log.info("Skip analysis task {} because it is not pending", taskId);
            return;
        }
        try {
            AnalysisTask task = taskRepository.findById(taskId).orElseThrow();
            Resume resume = resumeRepository.findById(task.getResumeId()).orElseThrow();
            JobDescription job = jobRepository.findById(task.getJobDescriptionId()).orElseThrow();
            InMemoryVectorStore vectorStore = new InMemoryVectorStore(embeddingClient);
            vectorStore.addAll(textChunker.chunk(resume.getRawText()));
            List<String> retrievedChunks = vectorStore.search(job.getContent(), 3).stream()
                .map(VectorSearchResult::text)
                .toList();
            String prompt = ragContextBuilder.build(
                retrievedChunks,
                job.getContent(),
                Arrays.stream(job.getSkillTags().split(",")).filter(tag -> !tag.isBlank()).toList()
            );
            String report = aiClient.complete(prompt);
            taskService.completeSuccess(new MatchReport(task.getId(), reportParser.extractScore(report), report));
        } catch (NoSuchElementException ex) {
            taskService.markFinalFailure(taskId, AnalysisFailureCode.SOURCE_DATA_MISSING, "Analysis source data is missing");
        } catch (IllegalArgumentException ex) {
            taskService.markFinalFailure(taskId, AnalysisFailureCode.REPORT_PARSE_FAILED, ex.getMessage());
        } catch (RuntimeException ex) {
            taskService.markRetryableFailure(taskId, AnalysisFailureCode.AI_UNAVAILABLE, ex.getMessage());
        }
    }
}
```

Keep the current RAG prompt building behavior unchanged.

- [ ] **Step 5: Update `AnalysisTaskService` failure methods**

Expose:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void markRetryableFailure(Long taskId, AnalysisFailureCode failureCode, String failureMessage) {
    taskRepository.markFailure(
        taskId,
        AnalysisTask.Status.FAILED_RETRYABLE,
        failureCode,
        failureMessage,
        LocalDateTime.now()
    );
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void markFinalFailure(Long taskId, AnalysisFailureCode failureCode, String failureMessage) {
    taskRepository.markFailure(
        taskId,
        AnalysisTask.Status.FAILED_FINAL,
        failureCode,
        failureMessage,
        LocalDateTime.now()
    );
}
```

Keep `markFailed(Long taskId)` only if existing tests still need it; otherwise remove it and update callers.

- [ ] **Step 6: Thin `AnalysisWorker`**

Replace worker dependencies with:

```java
private final RunAnalysisUseCase runAnalysisUseCase;
```

`handle(Long taskId, Boolean redelivered)` should only normalize the Boolean and call:

```java
runAnalysisUseCase.run(taskId, Boolean.TRUE.equals(redelivered));
```

- [ ] **Step 7: Run focused tests**

Run:

```powershell
mvn "-Dtest=RunAnalysisUseCaseTest,AnalysisWorkerTest,AnalysisTaskServiceTest" test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/zhulikang/aimatch/application/analysis/RunAnalysisUseCase.java src/main/java/com/zhulikang/aimatch/analysis/AnalysisWorker.java src/main/java/com/zhulikang/aimatch/analysis/AnalysisTaskService.java src/test/java/com/zhulikang/aimatch/application/analysis/RunAnalysisUseCaseTest.java src/test/java/com/zhulikang/aimatch/analysis/AnalysisWorkerTest.java src/test/java/com/zhulikang/aimatch/analysis/AnalysisTaskServiceTest.java
git commit -m "refactor: move analysis orchestration to use case"
```

---

### Task 5: Full Phase Verification and Documentation Touch-Up

**Files:**
- Review: `docs/architecture.md`
- Review: `docs/operations/runbook.md`
- Review all Phase 2 files.

- [ ] **Step 1: Run full fast tests**

Run:

```powershell
mvn test
```

Expected: PASS with all tests green.

- [ ] **Step 2: Inspect status and log**

Run:

```powershell
git status --short --branch
git log --oneline -8
```

Expected: only intentional docs/checklist edits, or clean after commits.

- [ ] **Step 3: Acceptance checklist**

Verify manually:

- Controller delegates to application use cases.
- Resume upload, JD creation, analysis creation, status lookup, report lookup still preserve existing API responses.
- `POST /api/analysis/{taskId}/retry` exists and only retries `FAILED_RETRYABLE`.
- `AnalysisTask` exposes status, attempts, failure code/message, started/completed timestamps.
- `AnalysisWorker` delegates to `RunAnalysisUseCase`.
- `RunAnalysisUseCase` owns RAG + AI orchestration and failure classification.
- No Flyway, outbox, Docker, Actuator, Testcontainers, frontend, account, RBAC, billing, multi-tenant, or external vector DB work slipped into this phase.

- [ ] **Step 4: Update docs if implementation changed architecture wording**

If needed, update `docs/architecture.md` so Phase 2 current state reflects application use cases and enriched task state.

- [ ] **Step 5: Run final full tests**

Run:

```powershell
mvn test
```

Expected: PASS.

- [ ] **Step 6: Commit final docs/checklist update if any**

If docs changed:

```powershell
git add docs/architecture.md docs/operations/runbook.md
git commit -m "docs: update phase 2 architecture notes"
```

If no docs changed, do not create an empty commit.

# Phase 1 Safety Net and Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the regression safety net and explicit API contracts needed before deeper engineering refactors.

**Architecture:** Keep the current Spring Boot package layout for this phase, but introduce small contract classes around HTTP responses, API errors, upload validation, task status views, and report parsing. The behavior remains backward compatible for existing clients while adding fields required by the engineering-hardening design.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring MVC, Spring Data JPA, RabbitMQ abstraction through existing service mocks, JUnit 5, Mockito, AssertJ, MockMvc.

---

## Scope

This plan implements Phase 1 from `docs/superpowers/specs/2026-07-04-engineering-hardening-design.md`.

Covered here:

- Explicit response DTOs for existing API success responses.
- Controller success-path response body tests.
- Structured API error responses.
- Upload file validation before document parsing.
- Task status endpoint contract.
- Report score parsing extracted from `AnalysisWorker` into a focused parser component.

Separate phase plans should cover application/domain package refactors, Flyway, outbox, Docker, observability, and Testcontainers.

## File Structure

- Modify: `src/main/java/com/zhulikang/aimatch/api/ResumeMatchController.java`
  - Return explicit DTO records.
  - Use upload validator.
  - Add `GET /api/analysis/{taskId}`.
- Modify: `src/main/java/com/zhulikang/aimatch/api/ApiExceptionHandler.java`
  - Return structured `ApiErrorResponse`.
- Create: `src/main/java/com/zhulikang/aimatch/api/ApiErrorResponse.java`
  - Stable error response shape.
- Modify: `src/main/java/com/zhulikang/aimatch/api/ApiTokenInterceptor.java`
  - Return structured authentication errors and compare tokens safely.
- Create: `src/main/java/com/zhulikang/aimatch/api/ResumeUploadResponse.java`
  - Response for resume upload.
- Create: `src/main/java/com/zhulikang/aimatch/api/JobDescriptionResponse.java`
  - Response for JD creation.
- Create: `src/main/java/com/zhulikang/aimatch/api/AnalysisTaskResponse.java`
  - Response for analysis creation and task status.
- Create: `src/main/java/com/zhulikang/aimatch/document/ResumeFileValidator.java`
  - File upload validation for empty file, size, and extension.
- Create: `src/main/java/com/zhulikang/aimatch/analysis/ReportParser.java`
  - Extract and clamp match score from AI output.
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisWorker.java`
  - Delegate score extraction to `ReportParser`.
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisService.java`
  - Add `findTask(Long taskId)`.
- Modify: `src/test/java/com/zhulikang/aimatch/api/ResumeMatchControllerTest.java`
  - Add success-path JSON assertions and task status endpoint tests.
- Create: `src/test/java/com/zhulikang/aimatch/document/ResumeFileValidatorTest.java`
  - Validate upload file contract.
- Create: `src/test/java/com/zhulikang/aimatch/analysis/ReportParserTest.java`
  - Validate score extraction contract.
- Modify: `src/test/java/com/zhulikang/aimatch/analysis/AnalysisWorkerTest.java`
  - Remove direct private-ish score parsing expectation and verify parser delegation through worker behavior.

---

### Task 1: API Success Response Contracts

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/api/ResumeUploadResponse.java`
- Create: `src/main/java/com/zhulikang/aimatch/api/JobDescriptionResponse.java`
- Create: `src/main/java/com/zhulikang/aimatch/api/AnalysisTaskResponse.java`
- Modify: `src/main/java/com/zhulikang/aimatch/api/ResumeMatchController.java`
- Modify: `src/test/java/com/zhulikang/aimatch/api/ResumeMatchControllerTest.java`

- [ ] **Step 1: Write failing controller success response tests**

Replace `src/test/java/com/zhulikang/aimatch/api/ResumeMatchControllerTest.java` imports with these imports, preserving the existing package declaration:

```java
import com.zhulikang.aimatch.analysis.AnalysisService;
import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.MatchReportView;
import com.zhulikang.aimatch.document.DocumentTextExtractor;
import com.zhulikang.aimatch.document.ResumeFileValidator;
import com.zhulikang.aimatch.job.JdTagExtractor;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
```

Add this mock field beside the other `@MockBean` fields:

```java
    @MockBean
    ResumeFileValidator resumeFileValidator;
```

Add these tests to `ResumeMatchControllerTest`:

```java
    @Test
    void uploadsResumeAndReturnsResumeId() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "resume.pdf",
            "application/pdf",
            new byte[] {1, 2, 3}
        );
        Resume savedResume = new Resume("resume.pdf", "Java Redis", "Java Redis");
        ReflectionTestUtils.setField(savedResume, "id", 10L);
        when(extractor.extract(file)).thenReturn("Java Redis");
        when(resumeRepository.save(any(Resume.class))).thenReturn(savedResume);

        mockMvc.perform(multipart("/api/resumes")
                .file(file)
                .header("X-API-Token", "test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resumeId").value(10));

        verify(resumeFileValidator).validate(file);
    }

    @Test
    void createsJobAndReturnsJobDescriptionId() throws Exception {
        JobDescription savedJob = new JobDescription("Java Redis", "Java,Redis");
        ReflectionTestUtils.setField(savedJob, "id", 20L);
        when(jdTagExtractor.extractTags("Java Redis")).thenReturn(List.of("Java", "Redis"));
        when(jdTagExtractor.toStorageValue(List.of("Java", "Redis"))).thenReturn("Java,Redis");
        when(jobRepository.save(any(JobDescription.class))).thenReturn(savedJob);

        mockMvc.perform(post("/api/jobs")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Java Redis\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobDescriptionId").value(20));
    }

    @Test
    void createsAnalysisAndReturnsTaskIdAndStatus() throws Exception {
        AnalysisTask savedTask = new AnalysisTask(10L, 20L);
        ReflectionTestUtils.setField(savedTask, "id", 30L);
        when(resumeRepository.existsById(10L)).thenReturn(true);
        when(jobRepository.existsById(20L)).thenReturn(true);
        when(analysisService.createTask(10L, 20L)).thenReturn(savedTask);

        mockMvc.perform(post("/api/analysis")
                .header("X-API-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resumeId\":10,\"jobDescriptionId\":20}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.taskId").value(30))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.resumeId").value(10))
            .andExpect(jsonPath("$.jobDescriptionId").value(20));
    }

    @Test
    void returnsReportBodyWhenReportExists() throws Exception {
        MatchReportView report = new MatchReportView(
            30L,
            88,
            "匹配分数：88",
            LocalDateTime.of(2026, 7, 4, 9, 30)
        );
        when(analysisService.findReport(30L)).thenReturn(Optional.of(report));

        mockMvc.perform(get("/api/analysis/30/report").header("X-API-Token", "test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.taskId").value(30))
            .andExpect(jsonPath("$.matchScore").value(88))
            .andExpect(jsonPath("$.reportContent").value("匹配分数：88"))
            .andExpect(jsonPath("$.createdAt").exists());
    }
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
mvn -Dtest=ResumeMatchControllerTest test
```

Expected: FAIL because `ResumeFileValidator`, response DTOs, and `status` response fields do not exist yet.

- [ ] **Step 3: Create response DTO records**

Create `src/main/java/com/zhulikang/aimatch/api/ResumeUploadResponse.java`:

```java
package com.zhulikang.aimatch.api;

public record ResumeUploadResponse(Long resumeId) {
}
```

Create `src/main/java/com/zhulikang/aimatch/api/JobDescriptionResponse.java`:

```java
package com.zhulikang.aimatch.api;

public record JobDescriptionResponse(Long jobDescriptionId) {
}
```

Create `src/main/java/com/zhulikang/aimatch/api/AnalysisTaskResponse.java`:

```java
package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.analysis.AnalysisTask;

import java.time.LocalDateTime;

public record AnalysisTaskResponse(
    Long taskId,
    Long resumeId,
    Long jobDescriptionId,
    String status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static AnalysisTaskResponse from(AnalysisTask task) {
        return new AnalysisTaskResponse(
            task.getId(),
            task.getResumeId(),
            task.getJobDescriptionId(),
            task.getStatus().name(),
            task.getCreatedAt(),
            task.getUpdatedAt()
        );
    }
}
```

- [ ] **Step 4: Modify controller to return DTOs and call upload validator**

Update `src/main/java/com/zhulikang/aimatch/api/ResumeMatchController.java` constructor and methods so the class has this shape:

```java
package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.analysis.AnalysisService;
import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.MatchReportView;
import com.zhulikang.aimatch.document.DocumentTextExtractor;
import com.zhulikang.aimatch.document.ResumeFileValidator;
import com.zhulikang.aimatch.job.JdTagExtractor;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ResumeMatchController {
    private final DocumentTextExtractor extractor;
    private final ResumeFileValidator resumeFileValidator;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobRepository;
    private final JdTagExtractor jdTagExtractor;
    private final AnalysisService analysisService;

    public ResumeMatchController(
        DocumentTextExtractor extractor,
        ResumeFileValidator resumeFileValidator,
        ResumeRepository resumeRepository,
        JobDescriptionRepository jobRepository,
        JdTagExtractor jdTagExtractor,
        AnalysisService analysisService
    ) {
        this.extractor = extractor;
        this.resumeFileValidator = resumeFileValidator;
        this.resumeRepository = resumeRepository;
        this.jobRepository = jobRepository;
        this.jdTagExtractor = jdTagExtractor;
        this.analysisService = analysisService;
    }

    @PostMapping("/resumes")
    public ResumeUploadResponse uploadResume(@RequestParam("file") MultipartFile file) {
        resumeFileValidator.validate(file);
        String rawText = extractor.extract(file);
        if (rawText.isBlank()) {
            throw new IllegalArgumentException("Resume text must not be blank");
        }
        Resume resume = resumeRepository.save(new Resume(file.getOriginalFilename(), rawText, rawText));
        return new ResumeUploadResponse(resume.getId());
    }

    @PostMapping("/jobs")
    public JobDescriptionResponse createJob(@Valid @RequestBody CreateJobRequest request) {
        String tags = jdTagExtractor.toStorageValue(jdTagExtractor.extractTags(request.content()));
        JobDescription job = jobRepository.save(new JobDescription(request.content(), tags));
        return new JobDescriptionResponse(job.getId());
    }

    @PostMapping("/analysis")
    public AnalysisTaskResponse createAnalysis(@Valid @RequestBody CreateAnalysisRequest request) {
        if (!resumeRepository.existsById(request.resumeId())) {
            throw new ResourceNotFoundException("Resume not found");
        }
        if (!jobRepository.existsById(request.jobDescriptionId())) {
            throw new ResourceNotFoundException("Job description not found");
        }
        AnalysisTask task = analysisService.createTask(request.resumeId(), request.jobDescriptionId());
        return AnalysisTaskResponse.from(task);
    }

    @GetMapping("/analysis/{taskId}/report")
    public ResponseEntity<MatchReportView> report(@PathVariable Long taskId) {
        return analysisService.findReport(taskId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

Create a temporary minimal validator so this task compiles. The validator is expanded in Task 3.

Create `src/main/java/com/zhulikang/aimatch/document/ResumeFileValidator.java`:

```java
package com.zhulikang.aimatch.document;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class ResumeFileValidator {
    public void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Resume file must not be empty");
        }
    }
}
```

- [ ] **Step 5: Run focused test and verify it passes**

Run:

```powershell
mvn -Dtest=ResumeMatchControllerTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/zhulikang/aimatch/api/ResumeUploadResponse.java src/main/java/com/zhulikang/aimatch/api/JobDescriptionResponse.java src/main/java/com/zhulikang/aimatch/api/AnalysisTaskResponse.java src/main/java/com/zhulikang/aimatch/api/ResumeMatchController.java src/main/java/com/zhulikang/aimatch/document/ResumeFileValidator.java src/test/java/com/zhulikang/aimatch/api/ResumeMatchControllerTest.java
git commit -m "test: lock api success response contracts"
```

---

### Task 2: Structured API Error Responses

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/api/ApiErrorResponse.java`
- Modify: `src/main/java/com/zhulikang/aimatch/api/ApiExceptionHandler.java`
- Modify: `src/main/java/com/zhulikang/aimatch/api/ApiTokenInterceptor.java`
- Modify: `src/test/java/com/zhulikang/aimatch/api/ResumeMatchControllerTest.java`

- [ ] **Step 1: Write failing structured error tests**

Add these assertions to existing error tests in `ResumeMatchControllerTest`.

For `rejectsRequestWithoutApiToken`, replace the assertion chain with:

```java
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("Unauthorized"));
```

Add this test to verify invalid tokens use the same response contract:

```java
    @Test
    void rejectsRequestWithInvalidApiToken() throws Exception {
        mockMvc.perform(get("/api/analysis/1/report").header("X-API-Token", "wrong-token"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("Unauthorized"));
    }
```

For `returnsBadRequestWhenJobContentIsBlank`, replace the assertion chain with:

```java
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid request"));
```

For `returnsBadRequestWhenAnalysisIdsAreMissing`, replace the assertion chain with:

```java
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid request"));
```

For `returnsBadRequestWhenAnalysisIdsAreNotPositive`, replace the assertion chain with:

```java
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid request"));
```

For `returnsBadRequestWhenUploadedResumeHasNoText`, replace the assertion chain with:

```java
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("Resume text must not be blank"));
```

For `returnsNotFoundWhenResumeDoesNotExist`, replace the assertion chain with:

```java
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Resume not found"));
```

- [ ] **Step 2: Run focused test and verify it fails**

Run:

```powershell
mvn -Dtest=ResumeMatchControllerTest test
```

Expected: FAIL because errors still use the old `{"error":"..."}` shape and auth failures do not return a JSON body.

- [ ] **Step 3: Create `ApiErrorResponse`**

Create `src/main/java/com/zhulikang/aimatch/api/ApiErrorResponse.java`:

```java
package com.zhulikang.aimatch.api;

public record ApiErrorResponse(String code, String message) {
}
```

- [ ] **Step 4: Update exception handler**

Replace `src/main/java/com/zhulikang/aimatch/api/ApiExceptionHandler.java` with:

```java
package com.zhulikang.aimatch.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse("INVALID_REQUEST", "Invalid request"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse("BAD_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(404).body(new ApiErrorResponse("NOT_FOUND", ex.getMessage()));
    }
}
```

- [ ] **Step 5: Update token interceptor to return structured auth errors**

Replace `src/main/java/com/zhulikang/aimatch/api/ApiTokenInterceptor.java` with:

```java
package com.zhulikang.aimatch.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ApiTokenInterceptor implements HandlerInterceptor {
    private static final String HEADER_NAME = "X-API-Token";

    private final String apiToken;
    private final ObjectMapper objectMapper;

    public ApiTokenInterceptor(@Value("${api.token}") String apiToken, ObjectMapper objectMapper) {
        this.apiToken = apiToken;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (apiToken == null || apiToken.isBlank() || !matches(request.getHeader(HEADER_NAME))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), new ApiErrorResponse("UNAUTHORIZED", "Unauthorized"));
            return false;
        }
        return true;
    }

    private boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        byte[] expected = apiToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = candidate.getBytes(StandardCharsets.UTF_8);
        return expected.length == actual.length && MessageDigest.isEqual(expected, actual);
    }
}
```

- [ ] **Step 6: Run focused test and verify it passes**

Run:

```powershell
mvn -Dtest=ResumeMatchControllerTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/zhulikang/aimatch/api/ApiErrorResponse.java src/main/java/com/zhulikang/aimatch/api/ApiExceptionHandler.java src/main/java/com/zhulikang/aimatch/api/ApiTokenInterceptor.java src/test/java/com/zhulikang/aimatch/api/ResumeMatchControllerTest.java
git commit -m "feat: return structured api errors"
```

---

### Task 3: Resume File Upload Validation

**Files:**
- Modify: `src/main/java/com/zhulikang/aimatch/document/ResumeFileValidator.java`
- Create: `src/test/java/com/zhulikang/aimatch/document/ResumeFileValidatorTest.java`
- Modify: `src/test/java/com/zhulikang/aimatch/api/ResumeMatchControllerTest.java`

- [ ] **Step 1: Write failing validator tests**

Create `src/test/java/com/zhulikang/aimatch/document/ResumeFileValidatorTest.java`:

```java
package com.zhulikang.aimatch.document;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeFileValidatorTest {
    private final ResumeFileValidator validator = new ResumeFileValidator(DataSize.ofBytes(5));

    @Test
    void rejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> validator.validate(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Resume file must not be empty");
    }

    @Test
    void rejectsFileLargerThanLimit() {
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[] {1, 2, 3, 4, 5, 6});

        assertThatThrownBy(() -> validator.validate(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Resume file exceeds the configured maximum size");
    }

    @Test
    void rejectsUnsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "resume.txt", "text/plain", new byte[] {1});

        assertThatThrownBy(() -> validator.validate(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Only PDF and DOCX are supported");
    }

    @Test
    void acceptsPdfAndDocxExtensionsCaseInsensitively() {
        validator.validate(new MockMultipartFile("file", "RESUME.PDF", "application/pdf", new byte[] {1}));
        validator.validate(new MockMultipartFile("file", "resume.DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", new byte[] {1}));
    }
}
```

Add this controller test to `ResumeMatchControllerTest`:

```java
    @Test
    void returnsBadRequestWhenUploadValidatorRejectsFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "resume.txt",
            "text/plain",
            new byte[] {1}
        );
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Only PDF and DOCX are supported"))
            .when(resumeFileValidator)
            .validate(file);

        mockMvc.perform(multipart("/api/resumes")
                .file(file)
                .header("X-API-Token", "test-token"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("Only PDF and DOCX are supported"));
    }
```

- [ ] **Step 2: Run focused tests and verify they fail**

Run:

```powershell
mvn -Dtest=ResumeFileValidatorTest,ResumeMatchControllerTest test
```

Expected: FAIL because `ResumeFileValidator` does not yet accept `DataSize` constructor or enforce size and extension.

- [ ] **Step 3: Implement validator**

Replace `src/main/java/com/zhulikang/aimatch/document/ResumeFileValidator.java` with:

```java
package com.zhulikang.aimatch.document;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;

@Component
public class ResumeFileValidator {
    private final DataSize maxFileSize;

    public ResumeFileValidator(@Value("${resume.upload.max-file-size:5MB}") DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Resume file must not be empty");
        }
        if (file.getSize() > maxFileSize.toBytes()) {
            throw new IllegalArgumentException("Resume file exceeds the configured maximum size");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".pdf") && !name.endsWith(".docx")) {
            throw new IllegalArgumentException("Only PDF and DOCX are supported");
        }
    }
}
```

- [ ] **Step 4: Run focused tests and verify they pass**

Run:

```powershell
mvn -Dtest=ResumeFileValidatorTest,ResumeMatchControllerTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/zhulikang/aimatch/document/ResumeFileValidator.java src/test/java/com/zhulikang/aimatch/document/ResumeFileValidatorTest.java src/test/java/com/zhulikang/aimatch/api/ResumeMatchControllerTest.java
git commit -m "feat: validate resume uploads before parsing"
```

---

### Task 4: Analysis Task Status Endpoint

**Files:**
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisService.java`
- Modify: `src/main/java/com/zhulikang/aimatch/api/ResumeMatchController.java`
- Modify: `src/test/java/com/zhulikang/aimatch/api/ResumeMatchControllerTest.java`

- [ ] **Step 1: Write failing task status endpoint tests**

Add these tests to `ResumeMatchControllerTest`:

```java
    @Test
    void returnsAnalysisTaskStatus() throws Exception {
        AnalysisTask task = new AnalysisTask(10L, 20L);
        ReflectionTestUtils.setField(task, "id", 30L);
        when(analysisService.findTask(30L)).thenReturn(Optional.of(task));

        mockMvc.perform(get("/api/analysis/30").header("X-API-Token", "test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.taskId").value(30))
            .andExpect(jsonPath("$.resumeId").value(10))
            .andExpect(jsonPath("$.jobDescriptionId").value(20))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.createdAt").exists())
            .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void returnsNotFoundWhenAnalysisTaskDoesNotExist() throws Exception {
        when(analysisService.findTask(404L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/analysis/404").header("X-API-Token", "test-token"))
            .andExpect(status().isNotFound());
    }
```

- [ ] **Step 2: Run focused test and verify it fails**

Run:

```powershell
mvn -Dtest=ResumeMatchControllerTest test
```

Expected: FAIL because `AnalysisService.findTask` and `GET /api/analysis/{taskId}` do not exist.

- [ ] **Step 3: Add task lookup to service**

Add this method to `src/main/java/com/zhulikang/aimatch/analysis/AnalysisService.java`:

```java
    public Optional<AnalysisTask> findTask(Long taskId) {
        return taskRepository.findById(taskId);
    }
```

- [ ] **Step 4: Add status endpoint to controller**

Add this method to `src/main/java/com/zhulikang/aimatch/api/ResumeMatchController.java` above the report endpoint:

```java
    @GetMapping("/analysis/{taskId}")
    public ResponseEntity<AnalysisTaskResponse> taskStatus(@PathVariable Long taskId) {
        return analysisService.findTask(taskId)
            .map(AnalysisTaskResponse::from)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
```

- [ ] **Step 5: Run focused test and verify it passes**

Run:

```powershell
mvn -Dtest=ResumeMatchControllerTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/zhulikang/aimatch/analysis/AnalysisService.java src/main/java/com/zhulikang/aimatch/api/ResumeMatchController.java src/test/java/com/zhulikang/aimatch/api/ResumeMatchControllerTest.java
git commit -m "feat: expose analysis task status"
```

---

### Task 5: Report Parser Component

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/analysis/ReportParser.java`
- Create: `src/test/java/com/zhulikang/aimatch/analysis/ReportParserTest.java`
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisWorker.java`
- Modify: `src/test/java/com/zhulikang/aimatch/analysis/AnalysisWorkerTest.java`

- [ ] **Step 1: Write failing parser tests**

Create `src/test/java/com/zhulikang/aimatch/analysis/ReportParserTest.java`:

```java
package com.zhulikang.aimatch.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportParserTest {
    private final ReportParser parser = new ReportParser();

    @Test
    void extractsChineseColonScore() {
        assertThat(parser.extractScore("匹配分数：88\n技能匹配：Redis")).isEqualTo(88);
    }

    @Test
    void extractsAsciiColonScore() {
        assertThat(parser.extractScore("匹配分数: 77")).isEqualTo(77);
    }

    @Test
    void clampsScoreAboveOneHundred() {
        assertThat(parser.extractScore("匹配分数：150")).isEqualTo(100);
    }

    @Test
    void rejectsMissingScore() {
        assertThatThrownBy(() -> parser.extractScore("技能匹配：Redis"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("匹配分数");
    }
}
```

- [ ] **Step 2: Run parser test and verify it fails**

Run:

```powershell
mvn -Dtest=ReportParserTest test
```

Expected: FAIL because `ReportParser` does not exist.

- [ ] **Step 3: Implement parser**

Create `src/main/java/com/zhulikang/aimatch/analysis/ReportParser.java`:

```java
package com.zhulikang.aimatch.analysis;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReportParser {
    private static final Pattern SCORE_PATTERN = Pattern.compile("匹配分数\\s*[:：]\\s*(\\d{1,3})");

    public int extractScore(String report) {
        Matcher matcher = SCORE_PATTERN.matcher(report == null ? "" : report);
        if (!matcher.find()) {
            throw new IllegalArgumentException("AI report does not contain 匹配分数");
        }
        int score = Integer.parseInt(matcher.group(1));
        return Math.max(0, Math.min(100, score));
    }
}
```

- [ ] **Step 4: Refactor worker to use parser**

Replace `src/main/java/com/zhulikang/aimatch/analysis/AnalysisWorker.java` with:

```java
package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.ai.AiClient;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.rag.EmbeddingClient;
import com.zhulikang.aimatch.rag.InMemoryVectorStore;
import com.zhulikang.aimatch.rag.RagContextBuilder;
import com.zhulikang.aimatch.rag.TextChunker;
import com.zhulikang.aimatch.rag.VectorSearchResult;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class AnalysisWorker {
    private static final Logger log = LoggerFactory.getLogger(AnalysisWorker.class);

    private final AnalysisTaskService taskService;
    private final AnalysisTaskRepository taskRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobRepository;
    private final TextChunker textChunker;
    private final EmbeddingClient embeddingClient;
    private final RagContextBuilder ragContextBuilder;
    private final AiClient aiClient;
    private final ReportParser reportParser;

    public AnalysisWorker(
        AnalysisTaskService taskService,
        AnalysisTaskRepository taskRepository,
        ResumeRepository resumeRepository,
        JobDescriptionRepository jobRepository,
        TextChunker textChunker,
        EmbeddingClient embeddingClient,
        RagContextBuilder ragContextBuilder,
        AiClient aiClient,
        ReportParser reportParser
    ) {
        this.taskService = taskService;
        this.taskRepository = taskRepository;
        this.resumeRepository = resumeRepository;
        this.jobRepository = jobRepository;
        this.textChunker = textChunker;
        this.embeddingClient = embeddingClient;
        this.ragContextBuilder = ragContextBuilder;
        this.aiClient = aiClient;
        this.reportParser = reportParser;
    }

    public void handle(Long taskId) {
        handle(taskId, false);
    }

    @RabbitListener(queues = RabbitConfig.ANALYSIS_QUEUE)
    public void handle(Long taskId, @Header(name = AmqpHeaders.REDELIVERED, required = false) Boolean redelivered) {
        boolean isRedelivered = Boolean.TRUE.equals(redelivered);
        if (!taskService.tryStart(taskId, isRedelivered)) {
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
        } catch (RuntimeException ex) {
            taskService.markFailed(taskId);
            log.warn("Analysis task {} failed: {}", taskId, ex.getMessage());
        }
    }
}
```

- [ ] **Step 5: Update worker test constructor**

In `src/test/java/com/zhulikang/aimatch/analysis/AnalysisWorkerTest.java`, remove the `rejectsReportWithoutMatchScore` test because `ReportParserTest` now owns that contract.

Update the `worker()` helper to pass `new ReportParser()`:

```java
    private AnalysisWorker worker() {
        return new AnalysisWorker(
            taskService,
            taskRepository,
            resumeRepository,
            jobRepository,
            new TextChunker(),
            new HashingEmbeddingClient(),
            new RagContextBuilder(),
            aiClient,
            new ReportParser()
        );
    }
```

Remove the unused static import:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

- [ ] **Step 6: Run focused tests and verify they pass**

Run:

```powershell
mvn -Dtest=ReportParserTest,AnalysisWorkerTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/zhulikang/aimatch/analysis/ReportParser.java src/main/java/com/zhulikang/aimatch/analysis/AnalysisWorker.java src/test/java/com/zhulikang/aimatch/analysis/ReportParserTest.java src/test/java/com/zhulikang/aimatch/analysis/AnalysisWorkerTest.java
git commit -m "refactor: extract analysis report parser"
```

---

### Task 6: Full Phase Verification

**Files:**
- Review: all files changed in Tasks 1-5.

- [ ] **Step 1: Run full fast test suite**

Run:

```powershell
mvn test
```

Expected: PASS with all tests green.

- [ ] **Step 2: Inspect git status**

Run:

```powershell
git status --short
```

Expected: no unstaged or untracked implementation files.

- [ ] **Step 3: Review Phase 1 acceptance checklist**

Verify each item manually:

- Existing endpoints still exist.
- `/api/resumes` returns `resumeId`.
- `/api/jobs` returns `jobDescriptionId`.
- `/api/analysis` returns `taskId`, `resumeId`, `jobDescriptionId`, and `status`.
- `/api/analysis/{taskId}` returns task status when present.
- Validation and bad request errors return `code` and `message`.
- Unauthorized requests return `code` and `message`.
- Resume upload validation happens before text extraction.
- `AnalysisWorker` delegates score parsing to `ReportParser`.
- `mvn test` passes.

- [ ] **Step 4: Commit final plan checkbox update if task checkboxes were edited**

If this plan file was edited to mark checkboxes during execution, commit those edits:

```powershell
git add docs/superpowers/plans/2026-07-04-phase-1-safety-net-contracts.md
git commit -m "docs: update phase 1 execution checklist"
```

If checkboxes were not edited, do not create a commit for this step.

## Self-Review Notes

- Spec coverage: This plan covers Phase 1 only: API DTOs, response assertions, structured errors, file upload validation, task status endpoint, and report parser extraction.
- Deferred scope: application/domain package refactor, Flyway, outbox, Docker, observability, operations docs, and Testcontainers are intentionally split into separate phase plans because each subsystem can be implemented and verified independently.
- Type consistency: `AnalysisTaskResponse.from(AnalysisTask)`, `AnalysisService.findTask(Long)`, `ResumeFileValidator.validate(MultipartFile)`, `ReportParser.extractScore(String)`, and `ApiErrorResponse(String, String)` are used consistently across tasks.

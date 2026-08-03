# Frontend Product Experience Implementation Plan

> **Status:** Completed on 2026-07-12. All implementation, deterministic browser tests, Docker-backed verification, full-stack browser acceptance, documentation, and visual QA are complete.

> **Review workflow:** Each major task receives one combined specification/quality review. Important findings are fixed together and verified directly; repeated reviews without new evidence are avoided.

**Goal:** Build a production-shaped React frontend for the complete resume-to-job analysis flow, including history, resilient async task UX, same-origin proxying, and browser-level verification.

**Architecture:** Add a standalone `frontend/` Vite SPA served by an unprivileged Nginx container. Nginx and the Vite development proxy inject the existing API token server-side, while Spring Boot gains only the UI-facing write/query contracts required by the approved design. MySQL remains the source of truth, RabbitMQ remains ID-only, and the existing outbox/worker/report path stays intact.

**Tech Stack:** Java 21, Spring Boot 3.3, Flyway, MySQL, Redis, RabbitMQ, React 19, TypeScript 6, Vite 8, React Router 8, TanStack Query 5, React Hook Form, Zod 4, React Markdown, Vitest, Testing Library, MSW, Playwright, Nginx, Docker Compose.

---

## Starting Context

- Worktree: `<repository-root>\.worktrees\frontend-product-experience`
- Project: `<repository-root>\.worktrees\frontend-product-experience\ai-resume-match`
- Branch: `codex/frontend-product-experience`
- Approved design: `docs/superpowers/specs/2026-07-10-frontend-product-experience-design.md`
- Baseline: `mvn test` passes with 107 tests and 0 failures.
- Preserve the existing API endpoints and the ID-only RabbitMQ contract.
- Never log or persist raw resume/JD content in browser storage, test output, or application logs.

## File Map

### Backend enablement

- `src/main/resources/application-dev.yml`, `application-docker.yml`: valid Connector/J UTF-8 URLs.
- `src/main/resources/db/migration/V2__add_job_title.sql`: backward-compatible title column and data backfill.
- `job/JobDescription.java`: title-bearing job entity.
- `application/job/JobTitleNormalizer.java`: explicit or JD-derived title rules.
- `application/analysis/*`: enriched task detail, paginated history, summary, and atomic submission use cases.
- `api/*Response.java`, `ResumeMatchController.java`: stable HTTP contracts for the SPA.
- Existing unit/integration tests: regression coverage and real infrastructure verification.

### Frontend application

- `frontend/src/app/`: app providers, routes, shell, and error boundary.
- `frontend/src/shared/api/`: runtime-validated API contracts and fetch client.
- `frontend/src/shared/components/`: buttons, status badges, async states, pagination.
- `frontend/src/features/dashboard/`: summary and recent analyses.
- `frontend/src/features/analyses/`: history, submission, detail, polling, retry.
- `frontend/src/features/reports/`: safe Markdown report presentation.
- `frontend/src/styles/`: approved design tokens and responsive CSS.
- `frontend/src/test/`: MSW server and test setup.
- `frontend/e2e/`: deterministic and full-stack Playwright scenarios.

### Delivery

- `frontend/Dockerfile`, `frontend/nginx/default.conf.template`: static build and same-origin proxy.
- `docker-compose.yml`: frontend service and configurable host ports.
- `docker-compose.e2e.yml`, `e2e/mock-ai/server.mjs`: repeatable full-stack browser environment.
- README and `docs/`: current startup, architecture, development, and operations guidance.

---

### Task 1: Fix the Docker JDBC URL prerequisite

**Files:**
- Modify: `src/test/java/com/zhulikang/aimatch/config/DeploymentConfigurationTest.java`
- Modify: `src/main/resources/application-dev.yml`
- Modify: `src/main/resources/application-docker.yml`

- [x] **Step 1: Add the failing configuration test**

Add this test to `DeploymentConfigurationTest`:

```java
@Test
void datasourceUrlsUseConnectorSupportedUtf8Configuration() {
    for (String profile : List.of("dev", "docker")) {
        String url = yaml("src/main/resources/application-" + profile + ".yml")
            .getProperty("spring.datasource.url");

        assertThat(url)
            .doesNotContain("characterEncoding=utf8mb4")
            .contains("connectionCollation=utf8mb4_unicode_ci");
    }
}
```

Add `java.util.List` to the imports.

- [x] **Step 2: Run the focused test and confirm the expected failure**

Run:

```powershell
mvn "-Dtest=DeploymentConfigurationTest#datasourceUrlsUseConnectorSupportedUtf8Configuration" test
```

Expected: FAIL because both URLs contain `characterEncoding=utf8mb4`.

- [x] **Step 3: Correct both JDBC URLs**

Use these URL values:

```yaml
# application-dev.yml
url: ${MYSQL_URL:jdbc:mysql://localhost:3306/ai_resume_match?useUnicode=true&connectionCollation=utf8mb4_unicode_ci&serverTimezone=Asia/Shanghai}

# application-docker.yml
url: jdbc:mysql://mysql:3306/${MYSQL_DATABASE}?useUnicode=true&connectionCollation=utf8mb4_unicode_ci&serverTimezone=Asia/Shanghai
```

- [x] **Step 4: Verify configuration tests**

Run:

```powershell
mvn "-Dtest=DeploymentConfigurationTest" test
```

Expected: all deployment configuration tests PASS.

- [x] **Step 5: Commit the prerequisite fix**

```powershell
git add src/main/resources/application-dev.yml src/main/resources/application-docker.yml src/test/java/com/zhulikang/aimatch/config/DeploymentConfigurationTest.java
git commit -m "fix: use supported mysql utf8 connection settings"
```

---

### Task 2: Add backward-compatible job titles

**Files:**
- Create: `src/main/resources/db/migration/V2__add_job_title.sql`
- Create: `src/main/java/com/zhulikang/aimatch/application/job/JobTitleNormalizer.java`
- Create: `src/test/java/com/zhulikang/aimatch/application/job/JobTitleNormalizerTest.java`
- Modify: `src/main/java/com/zhulikang/aimatch/job/JobDescription.java`
- Modify: `src/main/java/com/zhulikang/aimatch/application/job/CreateJobDescriptionUseCase.java`
- Modify: `src/main/java/com/zhulikang/aimatch/api/CreateJobRequest.java`
- Modify: `src/main/java/com/zhulikang/aimatch/api/JobDescriptionResponse.java`
- Modify: `src/main/java/com/zhulikang/aimatch/api/ResumeMatchController.java`
- Modify: `src/test/java/com/zhulikang/aimatch/FlywayMigrationTest.java`
- Modify: `src/integration-test/java/com/zhulikang/aimatch/FlywayMigrationIT.java`
- Modify: all tests constructing `JobDescription`

- [x] **Step 1: Write failing title and migration tests**

Create `JobTitleNormalizerTest`:

```java
class JobTitleNormalizerTest {
    private final JobTitleNormalizer normalizer = new JobTitleNormalizer();

    @Test
    void prefersTrimmedExplicitTitle() {
        assertThat(normalizer.normalize("  AI 应用开发工程师  ", "第一行 JD"))
            .isEqualTo("AI 应用开发工程师");
    }

    @Test
    void derivesTitleFromFirstNonBlankJdLine() {
        assertThat(normalizer.normalize(null, "\n  高级后端工程师  \n负责 Java 平台"))
            .isEqualTo("高级后端工程师");
    }

    @Test
    void truncatesDerivedTitleAndFallsBackWhenNeeded() {
        assertThat(normalizer.normalize(null, "x".repeat(121))).hasSize(120);
        assertThat(normalizer.normalize(" ", " \n ")).isEqualTo("未命名岗位");
    }
}
```

Extend both migration tests with a `columnExists` helper and:

```java
assertThat(columnExists(connection, "job_description", "title")).isTrue();
```

- [x] **Step 2: Run the focused tests and confirm failure**

```powershell
mvn "-Dtest=JobTitleNormalizerTest,FlywayMigrationTest" test
```

Expected: compilation fails because `JobTitleNormalizer` does not exist, and the migration assertion cannot pass yet.

- [x] **Step 3: Add the Flyway migration**

Create `V2__add_job_title.sql`:

```sql
alter table job_description add column title varchar(120);

update job_description
set title = concat('岗位 ', id)
where title is null or trim(title) = '';

alter table job_description modify column title varchar(120) not null;
```

- [x] **Step 4: Add title normalization and entity support**

Create `JobTitleNormalizer`:

```java
@Component
public class JobTitleNormalizer {
    static final int MAX_LENGTH = 120;

    public String normalize(String explicitTitle, String content) {
        String candidate = firstNonBlank(explicitTitle, content);
        if (candidate == null) {
            return "未命名岗位";
        }
        String normalized = candidate.trim();
        return normalized.length() <= MAX_LENGTH
            ? normalized
            : normalized.substring(0, MAX_LENGTH);
    }

    private String firstNonBlank(String explicitTitle, String content) {
        if (explicitTitle != null && !explicitTitle.isBlank()) {
            return explicitTitle;
        }
        if (content == null) {
            return null;
        }
        return content.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .findFirst()
            .orElse(null);
    }
}
```

Change the entity constructor to:

```java
public JobDescription(String title, String content, String skillTags) {
    this.title = title;
    this.content = content;
    this.skillTags = skillTags;
}

public String getTitle() {
    return title;
}
```

Add `@Column(nullable = false, length = 120) private String title;`.

- [x] **Step 5: Preserve the old JSON contract while accepting a title**

Use these records and use-case signature:

```java
public record CreateJobRequest(
    @Size(max = 120) String title,
    @NotBlank String content
) {
}

public record JobDescriptionResponse(Long jobDescriptionId, String title) {
    public static JobDescriptionResponse from(JobDescription job) {
        return new JobDescriptionResponse(job.getId(), job.getTitle());
    }
}

public JobDescription create(String title, String content) {
    String normalizedTitle = jobTitleNormalizer.normalize(title, content);
    String tags = jdTagExtractor.toStorageValue(jdTagExtractor.extractTags(content));
    return jobRepository.save(new JobDescription(normalizedTitle, content, tags));
}
```

Update the controller to call `create(request.title(), request.content())` and return `JobDescriptionResponse.from(job)`. Existing bodies containing only `content` remain valid because `title` is nullable.

- [x] **Step 6: Update constructors and assertions**

Update every `new JobDescription(content, tags)` call to `new JobDescription(title, content, tags)`. Add controller assertions for the returned `title`, and verify the missing-title request derives `Java Redis` from the first line.

- [x] **Step 7: Run focused and full fast tests**

```powershell
mvn "-Dtest=JobTitleNormalizerTest,CreateJobDescriptionUseCaseTest,ResumeMatchControllerTest,FlywayMigrationTest" test
mvn test
```

Expected: all tests PASS.

- [x] **Step 8: Commit job-title support**

```powershell
git add src/main src/test src/integration-test
git commit -m "feat: add display titles to job descriptions"
```

---

### Task 3: Add enriched task details, history, and summary APIs

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/application/analysis/AnalysisTaskDetails.java`
- Create: `src/main/java/com/zhulikang/aimatch/application/analysis/AnalysisListItem.java`
- Create: `src/main/java/com/zhulikang/aimatch/application/analysis/AnalysisPage.java`
- Create: `src/main/java/com/zhulikang/aimatch/application/analysis/AnalysisSummary.java`
- Create: `src/main/java/com/zhulikang/aimatch/application/analysis/ListAnalysisTasksUseCase.java`
- Create: `src/main/java/com/zhulikang/aimatch/application/analysis/GetAnalysisSummaryUseCase.java`
- Create: `src/test/java/com/zhulikang/aimatch/application/analysis/ListAnalysisTasksUseCaseTest.java`
- Create: `src/test/java/com/zhulikang/aimatch/application/analysis/GetAnalysisSummaryUseCaseTest.java`
- Create: `src/main/java/com/zhulikang/aimatch/api/AnalysisListItemResponse.java`
- Create: `src/main/java/com/zhulikang/aimatch/api/AnalysisPageResponse.java`
- Create: `src/main/java/com/zhulikang/aimatch/api/AnalysisSummaryResponse.java`
- Modify: `AnalysisTaskRepository.java`, `MatchReportRepository.java`, `GetAnalysisTaskUseCase.java`
- Modify: `AnalysisTaskResponse.java`, `ResumeMatchController.java`, controller tests

- [x] **Step 1: Write failing application tests**

Use fixed entities with IDs and verify these contracts:

```java
@Test
void listsNewestTasksWithDisplayMetadataAndScore() {
    AnalysisPage page = useCase.list(null, 0, 20);

    assertThat(page.items()).singleElement().satisfies(item -> {
        assertThat(item.jobTitle()).isEqualTo("高级后端工程师");
        assertThat(item.resumeFileName()).isEqualTo("resume.pdf");
        assertThat(item.matchScore()).isEqualTo(88);
    });
}

@Test
void calculatesGlobalSummary() {
    AnalysisSummary summary = useCase.get();

    assertThat(summary.totalCount()).isEqualTo(12);
    assertThat(summary.successCount()).isEqualTo(10);
    assertThat(summary.inProgressCount()).isEqualTo(1);
    assertThat(summary.averageMatchScore()).isEqualByComparingTo("82.4");
}
```

Also add controller tests for:

```text
GET /api/analysis?page=0&size=20
GET /api/analysis?status=SUCCESS&page=0&size=20
GET /api/analysis/summary
GET /api/analysis/{taskId} with jobTitle, resumeFileName, matchScore
```

- [x] **Step 2: Run the new tests and confirm failure**

```powershell
mvn "-Dtest=ListAnalysisTasksUseCaseTest,GetAnalysisSummaryUseCaseTest,ResumeMatchControllerTest" test
```

Expected: compilation failure for the new view/use-case types.

- [x] **Step 3: Add repository query primitives**

Add:

```java
Page<AnalysisTask> findByStatus(AnalysisTask.Status status, Pageable pageable);

long countByStatus(AnalysisTask.Status status);

long countByStatusIn(Collection<AnalysisTask.Status> statuses);
```

to `AnalysisTaskRepository`, and:

```java
List<MatchReport> findAllByTaskIdIn(Collection<Long> taskIds);

@Query("select avg(report.matchScore) from MatchReport report")
Double averageMatchScore();
```

to `MatchReportRepository`.

- [x] **Step 4: Add the application view records**

Use these stable shapes:

```java
public record AnalysisTaskDetails(
    AnalysisTask task,
    String jobTitle,
    String resumeFileName,
    Integer matchScore
) {
}

public record AnalysisListItem(
    Long taskId,
    String jobTitle,
    String resumeFileName,
    AnalysisTask.Status status,
    Integer matchScore,
    int attemptCount,
    int maxAttempts,
    AnalysisFailureCode failureCode,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime completedAt
) {
}

public record AnalysisPage(
    List<AnalysisListItem> items,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}

public record AnalysisSummary(
    long totalCount,
    long successCount,
    long inProgressCount,
    long retryableFailureCount,
    BigDecimal averageMatchScore
) {
}
```

- [x] **Step 5: Implement batched list assembly and summary**

`ListAnalysisTasksUseCase.list` must:

1. Reject `page < 0` and `size < 1 || size > 100` with `IllegalArgumentException`.
2. Build `PageRequest.of(page, size, Sort.by(desc("createdAt"), desc("id")))`.
3. Query all or filtered tasks.
4. Batch `findAllById` for resume/job IDs and `findAllByTaskIdIn` for reports.
5. Map missing related rows to `ResourceNotFoundException` instead of returning partial private data.

`GetAnalysisSummaryUseCase.get` must use:

```java
long inProgress = taskRepository.countByStatusIn(List.of(PENDING, RUNNING));
BigDecimal average = Optional.ofNullable(reportRepository.averageMatchScore())
    .map(BigDecimal::valueOf)
    .map(value -> value.setScale(1, RoundingMode.HALF_UP))
    .orElse(null);
```

Update `GetAnalysisTaskUseCase.find` to return `Optional<AnalysisTaskDetails>` and load the single task's job, resume, and optional report.

- [x] **Step 6: Add API response records and endpoints**

`AnalysisTaskResponse` gains nullable `jobTitle`, `resumeFileName`, and `matchScore`, with both `from(AnalysisTask)` and `from(AnalysisTaskDetails)` factories.

Add:

```java
@GetMapping("/analysis")
public AnalysisPageResponse analyses(
    @RequestParam(required = false) AnalysisTask.Status status,
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "20") int size
) {
    return AnalysisPageResponse.from(listAnalysisTasksUseCase.list(status, page, size));
}

@GetMapping("/analysis/summary")
public AnalysisSummaryResponse analysisSummary() {
    return AnalysisSummaryResponse.from(getAnalysisSummaryUseCase.get());
}
```

Keep the existing POST `/api/analysis` and path-variable endpoints unchanged.

- [x] **Step 7: Verify focused and fast suites**

```powershell
mvn "-Dtest=ListAnalysisTasksUseCaseTest,GetAnalysisSummaryUseCaseTest,ResumeMatchControllerTest,DomainRepositoryTest" test
mvn test
```

Expected: all tests PASS; history ordering and nullable scores are covered.

- [x] **Step 8: Commit read APIs**

```powershell
git add src/main/java src/test/java
git commit -m "feat: expose analysis history and summary"
```

---

### Task 4: Add atomic multipart analysis submission

**Files:**
- Create: `application/resume/PreparedResume.java`
- Create: `application/resume/PrepareResumeUseCase.java`
- Create: `application/analysis/AnalysisTaskCreator.java`
- Create: `application/analysis/AnalysisSubmission.java`
- Create: `application/analysis/PersistAnalysisSubmissionUseCase.java`
- Create: `application/analysis/CreateAnalysisSubmissionUseCase.java`
- Create corresponding unit tests
- Create: `src/test/java/com/zhulikang/aimatch/application/analysis/AnalysisSubmissionTransactionTest.java`
- Modify: `UploadResumeUseCase.java`, `CreateAnalysisTaskUseCase.java`
- Modify: `ResumeMatchController.java`, `ResumeMatchControllerTest.java`
- Modify: `EndToEndAnalysisFlowIT.java`

- [x] **Step 1: Write failing preparation and submission tests**

Add tests proving:

```java
@Test
void preparesValidatedResumeWithoutWritingDatabaseState() {
    PreparedResume prepared = useCase.prepare(file);

    assertThat(prepared.fileName()).isEqualTo("resume.pdf");
    assertThat(prepared.rawText()).isEqualTo("Java Redis");
    verify(validator).validate(file);
}

@Test
void preparesBeforeEnteringPersistenceBoundary() {
    when(prepareResumeUseCase.prepare(file)).thenReturn(prepared);
    when(persistUseCase.persist(prepared, "Backend Engineer", "Java Redis"))
        .thenReturn(submission);

    assertThat(useCase.create(file, "Backend Engineer", "Java Redis"))
        .isSameAs(submission);
    InOrder order = inOrder(prepareResumeUseCase, persistUseCase);
    order.verify(prepareResumeUseCase).prepare(file);
    order.verify(persistUseCase).persist(prepared, "Backend Engineer", "Java Redis");
}
```

Add a controller multipart test with `file`, `jobTitle`, and `jobContent` parts and assert `taskId`, `jobTitle`, `resumeFileName`, and `PENDING`.

Add a Spring transaction test using the H2 test profile. Replace `AnalysisTaskCreator` with `@MockBean`, make it throw `IllegalStateException`, call `PersistAnalysisSubmissionUseCase.persist`, and assert both repositories remain empty:

```java
@SpringBootTest
class AnalysisSubmissionTransactionTest {
    @Autowired PersistAnalysisSubmissionUseCase persistUseCase;
    @Autowired ResumeRepository resumeRepository;
    @Autowired JobDescriptionRepository jobRepository;
    @MockBean AnalysisTaskCreator taskCreator;

    @Test
    void rollsBackResumeAndJobWhenTaskCreationFails() {
        when(taskCreator.create(anyLong(), anyLong()))
            .thenThrow(new IllegalStateException("task persistence failed"));

        assertThatThrownBy(() -> persistUseCase.persist(
            new PreparedResume("resume.pdf", "Java", "Java"),
            "Backend Engineer",
            "Java"
        )).isInstanceOf(IllegalStateException.class);

        assertThat(resumeRepository.count()).isZero();
        assertThat(jobRepository.count()).isZero();
    }
}
```

- [x] **Step 2: Run focused tests and confirm failure**

```powershell
mvn "-Dtest=PrepareResumeUseCaseTest,CreateAnalysisSubmissionUseCaseTest,ResumeMatchControllerTest" test
```

Expected: compilation failure for the new use cases and endpoint.

- [x] **Step 3: Extract resume preparation**

Use:

```java
public record PreparedResume(String fileName, String rawText, String structuredSummary) {
    public Resume toEntity() {
        return new Resume(fileName, rawText, structuredSummary);
    }
}

@Service
public class PrepareResumeUseCase {
    public PreparedResume prepare(MultipartFile file) {
        resumeFileValidator.validate(file);
        String rawText = extractor.extract(file);
        if (rawText.isBlank()) {
            throw new IllegalArgumentException("Resume text must not be blank");
        }
        return new PreparedResume(file.getOriginalFilename(), rawText, rawText);
    }
}
```

Refactor `UploadResumeUseCase.upload` to `return resumeRepository.save(prepareResumeUseCase.prepare(file).toEntity());`.

- [x] **Step 4: Extract the common task creator**

Move task save/outbox/metric behavior into:

```java
@Component
public class AnalysisTaskCreator {
    public AnalysisTask create(Long resumeId, Long jobDescriptionId) {
        AnalysisTask task = taskRepository.save(new AnalysisTask(resumeId, jobDescriptionId));
        publisher.publishAfterCommit(task.getId());
        metrics.taskCreated();
        return task;
    }
}
```

`CreateAnalysisTaskUseCase` keeps its existence checks and delegates to this component. This preserves the legacy API behavior without duplicating outbox creation.

- [x] **Step 5: Implement the atomic persistence boundary**

Use these records and services:

```java
public record AnalysisSubmission(
    AnalysisTask task,
    String jobTitle,
    String resumeFileName
) {
}

@Service
public class CreateAnalysisSubmissionUseCase {
    public AnalysisSubmission create(MultipartFile file, String jobTitle, String jobContent) {
        PreparedResume preparedResume = prepareResumeUseCase.prepare(file);
        return persistUseCase.persist(preparedResume, jobTitle, jobContent);
    }
}

@Service
public class PersistAnalysisSubmissionUseCase {
    @Transactional
    public AnalysisSubmission persist(
        PreparedResume preparedResume,
        String jobTitle,
        String jobContent
    ) {
        Resume resume = resumeRepository.save(preparedResume.toEntity());
        JobDescription job = createJobDescriptionUseCase.create(jobTitle, jobContent);
        AnalysisTask task = taskCreator.create(resume.getId(), job.getId());
        return new AnalysisSubmission(task, job.getTitle(), resume.getFileName());
    }
}
```

Because `PrepareResumeUseCase` is a separate bean called before `PersistAnalysisSubmissionUseCase`, parser work occurs before the database transaction.

- [x] **Step 6: Add the multipart endpoint**

Add:

```java
@PostMapping(value = "/analysis-submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public AnalysisTaskResponse createAnalysisSubmission(
    @RequestParam("file") MultipartFile file,
    @RequestParam("jobTitle") @NotBlank @Size(max = 120) String jobTitle,
    @RequestParam("jobContent") @NotBlank String jobContent
) {
    AnalysisSubmission submission = createAnalysisSubmissionUseCase.create(
        file,
        jobTitle,
        jobContent
    );
    return AnalysisTaskResponse.from(submission);
}
```

Add an `AnalysisTaskResponse.from(AnalysisSubmission)` factory. Annotate the controller with `@Validated` so request-param constraints execute.

- [x] **Step 7: Extend the real E2E flow**

Change one DOCX test to submit through `/api/analysis-submissions`, then assert:

```text
GET /api/analysis/{taskId} -> SUCCESS with title and filename
GET /api/analysis?status=SUCCESS -> contains the task
GET /api/analysis/summary -> totalCount >= 1 and averageMatchScore = 91.0
```

Keep the PDF test on the legacy three-call path to preserve backward-compatibility coverage.

- [x] **Step 8: Verify fast tests**

```powershell
mvn "-Dtest=PrepareResumeUseCaseTest,UploadResumeUseCaseTest,CreateAnalysisSubmissionUseCaseTest,PersistAnalysisSubmissionUseCaseTest,ResumeMatchControllerTest" test
mvn test
```

Expected: 0 failures.

- [x] **Step 9: Commit atomic submission**

```powershell
git add src/main/java src/test/java src/integration-test/java
git commit -m "feat: add atomic analysis submission flow"
```

---

### Task 5: Scaffold the typed React application

**Files:**
- Create: `frontend/package.json`, `package-lock.json`
- Create: TypeScript, Vite, ESLint, Vitest configuration files
- Create: `frontend/index.html`
- Create: `frontend/src/main.tsx`
- Create: `frontend/src/app/App.tsx`, `router.tsx`, `queryClient.ts`
- Create: `frontend/src/styles/tokens.css`, `global.css`
- Create: `frontend/src/test/setup.ts`
- Create: `frontend/src/app/App.test.tsx`

- [x] **Step 1: Create the package manifest and install the lockfile**

Use this manifest, keeping the exact resolved lockfile generated by `npm install`:

```json
{
  "name": "ai-resume-match-frontend",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "engines": { "node": ">=24" },
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "lint": "eslint .",
    "typecheck": "tsc -b --pretty false",
    "test": "vitest run",
    "test:watch": "vitest",
    "test:e2e": "playwright test --project=chromium",
    "test:e2e:full-stack": "node scripts/run-full-stack-e2e.mjs"
  },
  "dependencies": {
    "@hookform/resolvers": "^5.4.0",
    "@tanstack/react-query": "^5.101.2",
    "lucide-react": "^1.24.0",
    "react": "^19.2.7",
    "react-dom": "^19.2.7",
    "react-hook-form": "^7.81.0",
    "react-markdown": "^10.1.0",
    "react-router": "^8.2.0",
    "remark-gfm": "^4.0.1",
    "zod": "^4.4.3"
  },
  "devDependencies": {
    "@eslint/js": "^10.0.1",
    "@playwright/test": "^1.61.1",
    "@tanstack/eslint-plugin-query": "^5.101.2",
    "@testing-library/dom": "^10.4.1",
    "@testing-library/jest-dom": "^6.9.1",
    "@testing-library/react": "^16.3.2",
    "@testing-library/user-event": "^14.6.1",
    "@types/react": "^19.2.17",
    "@types/react-dom": "^19.2.3",
    "@vitejs/plugin-react": "^6.0.3",
    "eslint": "^10.6.0",
    "eslint-plugin-jsx-a11y": "^6.10.2",
    "eslint-plugin-react-hooks": "^7.1.1",
    "eslint-plugin-react-refresh": "^0.5.3",
    "globals": "^17.7.0",
    "jsdom": "^29.1.1",
    "msw": "^2.15.0",
    "pdf-lib": "^1.17.1",
    "typescript": "^7.0.2",
    "typescript-eslint": "^8.63.0",
    "vite": "^8.1.4",
    "vitest": "^4.1.10"
  }
}
```

Run `npm install` in `frontend/` and commit `package-lock.json`; do not use CDN imports.

- [x] **Step 2: Add a failing app smoke test**

```tsx
test('renders the product shell and dashboard route', async () => {
  render(<App />)

  expect(await screen.findByRole('link', { name: 'MatchLab' })).toBeVisible()
  expect(screen.getByRole('heading', { name: '分析总览' })).toBeVisible()
})
```

Run `npm test -- App.test.tsx`; expect FAIL because `App` does not exist.

- [x] **Step 3: Add strict TypeScript, Vite, Vitest, and ESLint configuration**

Set `strict`, `noUncheckedIndexedAccess`, and `exactOptionalPropertyTypes` in `tsconfig.app.json`. Configure Vitest with `environment: 'jsdom'`, `setupFiles: ['./src/test/setup.ts']`, and CSS enabled. Configure Vite to proxy `/api` and `/backend-health`; load `API_TOKEN` without a `VITE_` prefix and set it only in proxy headers.

The proxy core must be:

```ts
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.API_PROXY_TARGET || 'http://localhost:8080'
  const headers = env.API_TOKEN ? { 'X-API-Token': env.API_TOKEN } : undefined

  return {
    plugins: [react()],
    server: {
      proxy: {
        '/api': { target, changeOrigin: true, headers },
        '/backend-health': {
          target,
          changeOrigin: true,
          rewrite: () => '/actuator/health/readiness',
        },
      },
    },
  }
})
```

- [x] **Step 4: Add providers and placeholder routes**

Create a `QueryClient` with one retry for GET queries and zero mutation retries. Use `createBrowserRouter` with the four approved routes and an `AppShell` placeholder. Import `tokens.css` and `global.css` from `main.tsx`.

- [x] **Step 5: Run frontend baseline checks**

```powershell
npm run lint
npm run typecheck
npm test
npm run build
```

Expected: all commands PASS and `dist/index.html` exists.

- [x] **Step 6: Commit the frontend scaffold**

```powershell
git add frontend
git commit -m "feat: scaffold typed react frontend"
```

---

### Task 6: Implement the runtime-validated API client

**Files:**
- Create: `frontend/src/shared/api/schemas.ts`
- Create: `frontend/src/shared/api/client.ts`
- Create: `frontend/src/shared/api/analyses.ts`
- Create: `frontend/src/shared/api/client.test.ts`
- Create: `frontend/src/test/server.ts`, `handlers.ts`
- Modify: `frontend/src/test/setup.ts`

- [x] **Step 1: Write failing API-client tests**

Cover success, structured errors, invalid JSON shape, and multipart submission:

```ts
test('throws a structured ApiError with request id', async () => {
  server.use(http.get('/api/analysis/404', () => HttpResponse.json(
    { code: 'NOT_FOUND', message: 'Analysis task not found', requestId: 'req-404' },
    { status: 404 },
  )))

  await expect(getAnalysisTask(404)).rejects.toMatchObject({
    code: 'NOT_FOUND',
    requestId: 'req-404',
    status: 404,
  })
})

test('rejects a response that violates the runtime schema', async () => {
  server.use(http.get('/api/analysis/1', () => HttpResponse.json({ taskId: 'bad' })))
  await expect(getAnalysisTask(1)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
})
```

- [x] **Step 2: Run the tests and confirm failure**

```powershell
npm test -- src/shared/api/client.test.ts
```

Expected: FAIL because the API client is missing.

- [x] **Step 3: Define Zod schemas matching the backend**

Define and export schemas for:

```ts
analysisStatusSchema
analysisTaskSchema
analysisListItemSchema
analysisPageSchema
analysisSummarySchema
matchReportSchema
apiErrorSchema
healthSchema
```

All server timestamps are ISO strings, `failureCode`, `failureMessage`, `jobTitle`, `resumeFileName`, `matchScore`, and `averageMatchScore` are nullable where the Java response permits null.

- [x] **Step 4: Implement one fetch boundary**

Use this behavior:

```ts
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly requestId?: string,
  ) {
    super(message)
  }
}

export async function apiRequest<T>(
  path: string,
  schema: ZodType<T>,
  init?: RequestInit,
): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      Accept: 'application/json',
      'X-Request-Id': crypto.randomUUID(),
      ...init?.headers,
    },
  })
  const payload: unknown = await response.json().catch(() => null)
  if (!response.ok) {
    const parsed = apiErrorSchema.safeParse(payload)
    throw new ApiError(
      response.status,
      parsed.success ? parsed.data.code : 'HTTP_ERROR',
      parsed.success ? parsed.data.message : '请求失败',
      parsed.success ? parsed.data.requestId ?? undefined : response.headers.get('X-Request-Id') ?? undefined,
    )
  }
  const parsed = schema.safeParse(payload)
  if (!parsed.success) {
    throw new ApiError(response.status, 'INVALID_RESPONSE', '服务返回了无法识别的数据')
  }
  return parsed.data
}
```

Do not log `payload` on parse failure.

- [x] **Step 5: Add endpoint functions**

Export:

```ts
getAnalysisSummary()
listAnalyses({ status, page, size })
createAnalysisSubmission({ file, jobTitle, jobContent })
getAnalysisTask(taskId)
retryAnalysisTask(taskId)
getMatchReport(taskId)
getBackendHealth()
```

`createAnalysisSubmission` must build a `FormData` and must not set `Content-Type` manually.

- [x] **Step 6: Verify the API layer**

```powershell
npm test -- src/shared/api/client.test.ts
npm run typecheck
npm run lint
```

Expected: PASS.

- [x] **Step 7: Commit the API layer**

```powershell
git add frontend/src/shared/api frontend/src/test
git commit -m "feat: add validated frontend api client"
```

---

### Task 7: Build the application shell and shared UI states

**Files:**
- Create: `frontend/src/app/AppShell.tsx`, `ErrorBoundary.tsx`
- Create: `frontend/src/shared/components/Button.tsx`, `StatusBadge.tsx`, `AsyncState.tsx`, `Pagination.tsx`
- Create component tests
- Modify: app router and shared CSS

- [x] **Step 1: Write failing shell and component tests**

Assert:

```tsx
expect(screen.getByRole('navigation', { name: '主导航' })).toBeVisible()
expect(screen.getByRole('link', { name: '总览' })).toHaveAttribute('href', '/')
expect(screen.getByRole('link', { name: '分析记录' })).toHaveAttribute('href', '/analyses')
expect(screen.getByRole('link', { name: '新建分析' })).toHaveAttribute('href', '/analyses/new')
expect(screen.getByText('可重试失败')).toHaveAttribute('data-status', 'FAILED_RETRYABLE')
```

Test the error boundary fallback and pagination disabled states.

- [x] **Step 2: Run focused tests and confirm failure**

```powershell
npm test -- src/app src/shared/components
```

- [x] **Step 3: Implement the approved shell**

Desktop: fixed-width dark ink sidebar, white top bar, constrained content. Mobile: horizontal navigation with no off-screen controls. Use Lucide `LayoutDashboard`, `History`, `Plus`, `RefreshCw`, `Copy`, and `AlertTriangle` icons; icon-only actions receive `aria-label` and `title`.

The health label must say `API 已连接` or `API 暂不可用`; do not claim database or queue health.

- [x] **Step 4: Implement shared states**

`StatusBadge` maps every backend enum to Chinese text and a non-color cue. `AsyncState` supports loading, empty, error, and content. `Button` has `primary`, `secondary`, `danger`, loading, and disabled states. `Pagination` uses icon buttons with visible current-page text.

- [x] **Step 5: Verify shared UI**

```powershell
npm test -- src/app src/shared/components
npm run lint
npm run typecheck
```

- [x] **Step 6: Commit the shell**

```powershell
git add frontend/src/app frontend/src/shared/components frontend/src/styles
git commit -m "feat: add frontend application shell"
```

---

### Task 8: Implement dashboard and history pages

**Files:**
- Create: `frontend/src/features/dashboard/DashboardPage.tsx`, hooks, tests, styles
- Create: `frontend/src/features/analyses/AnalysesPage.tsx`, hooks, tests, styles
- Modify: router

- [x] **Step 1: Write failing dashboard tests**

Use MSW and assert:

```tsx
expect(await screen.findByText('12')).toBeVisible()
expect(screen.getByText('10')).toBeVisible()
expect(screen.getByText('82.4')).toBeVisible()
expect(screen.getByRole('link', { name: /高级后端工程师/ })).toHaveAttribute('href', '/analyses/1')
```

Add separate tests for loading skeletons, empty recent history, and summary failure with a reload action.

- [x] **Step 2: Write failing history tests**

Verify status selection updates `?status=SUCCESS&page=0&size=20`, pagination updates the URL, empty filters differ from empty history, and a long title does not replace the accessible full name.

- [x] **Step 3: Run focused tests and confirm failure**

```powershell
npm test -- src/features/dashboard src/features/analyses/AnalysesPage.test.tsx
```

- [x] **Step 4: Implement TanStack Query hooks**

Use query keys:

```ts
['analysis-summary']
['analyses', { status, page, size }]
```

Dashboard requests summary and `{ page: 0, size: 5 }` in parallel. History derives filters from `useSearchParams` and never duplicates them into component state.

- [x] **Step 5: Implement responsive views**

Use three metric panels and a semantic table. At mobile width hide only the resume filename and completion-time columns, preserving title, score, and status. All loading placeholders keep the same grid/table dimensions as loaded content.

- [x] **Step 6: Verify both pages**

```powershell
npm test -- src/features/dashboard src/features/analyses/AnalysesPage.test.tsx
npm run lint
npm run typecheck
```

- [x] **Step 7: Commit dashboard and history**

```powershell
git add frontend/src/features/dashboard frontend/src/features/analyses frontend/src/app/router.tsx frontend/src/styles
git commit -m "feat: add analysis dashboard and history"
```

---

### Task 9: Implement the new-analysis form

**Files:**
- Create: `frontend/src/features/analyses/NewAnalysisPage.tsx`
- Create: `frontend/src/features/analyses/analysisFormSchema.ts`
- Create: `frontend/src/features/analyses/NewAnalysisPage.test.tsx`
- Modify: feature styles and router

- [x] **Step 1: Write failing form tests**

Cover:

```text
missing file
unsupported extension
file larger than 5 MB
blank title
title longer than 120 characters
blank JD
successful multipart request and navigation
server error preserves selected filename/title/JD
```

The success test must assert navigation to `/analyses/42` and that the summary/history queries are invalidated.

- [x] **Step 2: Run the focused test and confirm failure**

```powershell
npm test -- src/features/analyses/NewAnalysisPage.test.tsx
```

- [x] **Step 3: Add Zod form validation**

Use:

```ts
export const analysisFormSchema = z.object({
  file: z.instanceof(File)
    .refine(file => file.size > 0, '请选择非空简历文件')
    .refine(file => file.size <= 5 * 1024 * 1024, '简历文件不能超过 5 MB')
    .refine(file => /\.(pdf|docx)$/i.test(file.name), '仅支持 PDF 或 DOCX'),
  jobTitle: z.string().trim().min(1, '请输入岗位名称').max(120, '岗位名称不能超过 120 个字符'),
  jobContent: z.string().trim().min(1, '请输入岗位描述'),
})
```

- [x] **Step 4: Implement the accessible upload form**

Use a visible file label, drag/drop zone, accepted-format text, filename/size display, visible labels for title/JD, field-level errors, and a submission summary. Keep all values only in React Hook Form memory. On success call `reset()`, invalidate summary/history, then navigate.

- [x] **Step 5: Verify the form**

```powershell
npm test -- src/features/analyses/NewAnalysisPage.test.tsx
npm run lint
npm run typecheck
```

- [x] **Step 6: Commit the submission UI**

```powershell
git add frontend/src/features/analyses frontend/src/app/router.tsx
git commit -m "feat: add guided analysis submission"
```

---

### Task 10: Implement task progress, retry, and safe report rendering

**Files:**
- Create: `frontend/src/features/analyses/AnalysisDetailPage.tsx`
- Create: `frontend/src/features/analyses/useAnalysisTask.ts`
- Create: `frontend/src/features/reports/MatchReport.tsx`
- Create tests for polling, terminal states, retry, and Markdown
- Modify: feature styles and router

- [x] **Step 1: Write failing polling tests**

Use fake timers and prove:

```ts
PENDING -> refetch after 2 seconds
second active poll -> 4 seconds
later active polls -> capped at 8 seconds
SUCCESS / FAILED_RETRYABLE / FAILED_FINAL -> no further polling
```

Do not model a network failure as a business status change.

- [x] **Step 2: Write failing report and retry tests**

Assert that:

- `FAILED_RETRYABLE` exposes retry and invalidates task/history/summary after success.
- terminal failures show a copyable request ID but no retry.
- Markdown headings, lists, and tables render.
- raw `<script>` and raw HTML are displayed as text or omitted, never executed.
- external links receive `target="_blank"` and `rel="noreferrer noopener"`.

- [x] **Step 3: Run focused tests and confirm failure**

```powershell
npm test -- src/features/analyses/AnalysisDetailPage.test.tsx src/features/reports
```

- [x] **Step 4: Implement status-driven detail behavior**

Use a query `refetchInterval` function returning `2000`, `4000`, then `8000` based on successful active fetch count, and `false` for terminal states. Set `refetchIntervalInBackground: false`. Display task metadata, attempts, timestamps, last-known state on transport failure, and a manual refresh action.

- [x] **Step 5: Implement safe Markdown**

Render:

```tsx
<ReactMarkdown
  remarkPlugins={[remarkGfm]}
  components={{
    a: ({ href, children }) => (
      <a href={href} target="_blank" rel="noreferrer noopener">{children}</a>
    ),
  }}
>
  {report.reportContent}
</ReactMarkdown>
```

Do not install or enable `rehype-raw`.

- [x] **Step 6: Verify detail behavior**

```powershell
npm test -- src/features/analyses src/features/reports
npm run lint
npm run typecheck
npm run build
```

- [x] **Step 7: Commit the detail/report flow**

```powershell
git add frontend/src/features/analyses frontend/src/features/reports
git commit -m "feat: add resilient analysis detail experience"
```

---

### Task 11: Add Nginx and Docker Compose delivery

> Final hardening note: the reviewed implementation pins both frontend base-image digests and renders an escaped `API_TOKEN_NGINX` through `frontend-entrypoint.sh`. The original snippets below express the delivery intent; they must not be copied as the final secret-rendering implementation.

**Files:**
- Create: `frontend/Dockerfile`, `.dockerignore`
- Create: `frontend/nginx/default.conf.template`
- Modify: `docker-compose.yml`, `.env.example`
- Modify: `DeploymentConfigurationTest.java`

- [x] **Step 1: Write failing deployment asset tests**

Extend `DeploymentConfigurationTest` to assert:

```java
assertThat(compose).contains("frontend:")
    .contains("${FRONTEND_PORT:-3000}:8080")
    .contains("MYSQL_PORT")
    .contains("RABBITMQ_MANAGEMENT_PORT");
assertThat(frontendDockerfile).contains("FROM node:24-alpine")
    .contains("nginxinc/nginx-unprivileged")
    .contains("USER 101");
assertThat(nginxTemplate).contains("proxy_set_header X-API-Token ${API_TOKEN}")
    .contains("try_files $uri $uri/ /index.html")
    .contains("Content-Security-Policy");
```

- [x] **Step 2: Run the focused test and confirm failure**

```powershell
mvn "-Dtest=DeploymentConfigurationTest" test
```

- [x] **Step 3: Add the frontend multi-stage image**

Use:

```dockerfile
FROM node:24-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /workspace/dist /usr/share/nginx/html
USER 101
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:8080/frontend-health || exit 1
```

- [x] **Step 4: Add the Nginx template**

Required locations:

```nginx
location = /frontend-health { access_log off; return 200 "ok\n"; }
location = /backend-health { proxy_pass http://app:8080/actuator/health/readiness; }
location /api/ {
    proxy_pass http://app:8080;
    proxy_set_header X-API-Token ${API_TOKEN};
    proxy_set_header X-Request-Id $http_x_request_id;
    proxy_set_header X-Correlation-Id $http_x_correlation_id;
}
location / { try_files $uri $uri/ /index.html; }
```

Add CSP, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, and `X-Frame-Options: DENY`. Permit only same-origin scripts, styles, images, fonts, forms, and connections.

- [x] **Step 5: Extend Compose without breaking API access**

Add `frontend` with `API_TOKEN`, `${FRONTEND_PORT:-3000}:8080`, app health dependency, and restart policy. Make dependency host ports configurable:

```yaml
mysql:      "${MYSQL_PORT:-3306}:3306"
redis:      "${REDIS_PORT:-6379}:6379"
rabbitmq:   "${RABBITMQ_AMQP_PORT:-5672}:5672"
management: "${RABBITMQ_MANAGEMENT_PORT:-15672}:15672"
```

Document the four new variables in `.env.example`.

- [x] **Step 6: Verify build assets**

```powershell
mvn "-Dtest=DeploymentConfigurationTest" test
npm --prefix frontend run build
docker compose --env-file .env.example config --quiet
docker build -t ai-resume-match-frontend:local frontend
```

Expected: all commands succeed.

- [x] **Step 7: Commit delivery assets**

```powershell
git add frontend/Dockerfile frontend/.dockerignore frontend/nginx docker-compose.yml .env.example src/test/java/com/zhulikang/aimatch/config/DeploymentConfigurationTest.java
git commit -m "feat: deliver frontend through nginx compose service"
```

---

### Task 12: Add deterministic Playwright browser tests

**Files:**
- Create: `frontend/playwright.config.ts`
- Create: `frontend/e2e/mockApi.ts`
- Create: `frontend/e2e/product-flow.spec.ts`
- Create: `frontend/e2e/responsive.spec.ts`

- [x] **Step 1: Configure Chromium and the Vite web server**

Use:

```ts
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:4173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: process.env.PLAYWRIGHT_BASE_URL ? undefined : {
    command: 'npm run dev -- --host 127.0.0.1 --port 4173',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: false,
  },
})
```

- [x] **Step 2: Write the failing full product-flow test**

Intercept same-origin API calls and model:

```text
dashboard summary + recent list
multipart submission -> task 42 PENDING
task 42 -> PENDING, RUNNING, SUCCESS
report 42 -> score 88 and Markdown content
history -> includes task 42
```

Drive the page by accessible labels and roles only. Assert the user can move from dashboard to submission, upload an in-memory PDF payload, wait for the report, and return to history.

- [x] **Step 3: Add retry and error-path coverage**

Add a test where task 43 is `FAILED_RETRYABLE`, click `重新分析`, mock the POST retry response, then assert the page returns to `PENDING`. Add a final-failure case with visible request ID and no retry button.

- [x] **Step 4: Add responsive and overflow checks**

At `1440x900` and `390x844`, assert:

```ts
const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)
expect(overflow).toBe(false)
```

Capture stable screenshots for dashboard, new analysis, active task, and report.

- [x] **Step 5: Install the browser and run tests**

```powershell
npx --prefix frontend playwright install chromium
npm --prefix frontend run test:e2e
```

Expected: all Playwright tests PASS.

- [x] **Step 6: Commit browser tests**

```powershell
git add frontend/playwright.config.ts frontend/e2e
git commit -m "test: cover frontend product flow in browser"
```

---

### Task 13: Add real full-stack browser acceptance

**Files:**
- Create: `e2e/mock-ai/server.mjs`
- Create: `docker-compose.e2e.yml`
- Create: `frontend/e2e/full-stack.spec.ts`
- Create: `frontend/scripts/run-full-stack-e2e.mjs`
- Modify: `frontend/package.json`, lockfile if needed

- [x] **Step 1: Add a deterministic OpenAI-compatible mock service**

The Node server must expose `GET /health` and `POST /v1/chat/completions`, record no request bodies, and return:

```json
{
  "choices": [
    {
      "message": {
        "content": "匹配分数: 91\n\n## 核心结论\n候选人的 Java、Spring Boot、Redis 与 RabbitMQ 经验匹配岗位要求。"
      }
    }
  ]
}
```

- [x] **Step 2: Add the E2E Compose override**

Add `mock-ai` from `node:24-alpine`, mount `e2e/mock-ai/server.mjs` read-only, and override app `AI_ENDPOINT` to `http://mock-ai:18089/v1/chat/completions`. Add a Node-based healthcheck and make app depend on healthy mock AI.

- [x] **Step 3: Write the full-stack Playwright test**

Use `pdf-lib` to create a valid in-memory PDF:

```ts
const pdf = await PDFDocument.create()
const pdfPage = pdf.addPage([595, 842])
pdfPage.drawText('Java Spring Boot Redis RabbitMQ candidate', { x: 50, y: 780 })
const bytes = await pdf.save()

await page.getByLabel('简历文件').setInputFiles({
  name: 'resume.pdf',
  mimeType: 'application/pdf',
  buffer: Buffer.from(bytes),
})
```

Submit a synthetic JD, wait for score `91`, revisit history, and assert the job title and filename are present.

- [x] **Step 4: Add a cross-platform orchestration script**

`run-full-stack-e2e.mjs` must:

1. Set dedicated ports (`18080`, `18081`, `13308`, `16381`, `15678`, `15679`).
2. Run `docker compose -p ai-resume-match-frontend-e2e --env-file .env.example -f docker-compose.yml -f docker-compose.e2e.yml up -d --build --wait`.
3. Run only `e2e/full-stack.spec.ts` with `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080`.
4. Always run `docker compose ... down -v` in `finally`.
5. Forward child stdout/stderr and exit nonzero on failure without printing secrets.

- [x] **Step 5: Run full-stack acceptance**

```powershell
npm --prefix frontend run test:e2e:full-stack
```

Expected: Compose becomes healthy, the browser test reports score 91, and teardown removes containers/volumes.

- [x] **Step 6: Commit full-stack acceptance**

```powershell
git add e2e docker-compose.e2e.yml frontend/e2e/full-stack.spec.ts frontend/scripts frontend/package.json frontend/package-lock.json
git commit -m "test: add full stack frontend acceptance flow"
```

---

### Task 14: Update documentation and perform final verification

**Files:**
- Modify: `README.md`
- Modify: `docs/architecture.md`
- Modify: `docs/development.md`
- Modify: `docs/operations/runbook.md`
- Modify: this plan checklist as tasks complete

- [x] **Step 1: Update user and developer documentation**

Document:

```text
default frontend URL and API URL
frontend npm commands
same-origin token injection boundary
new backend endpoints and job title compatibility
Docker host-port overrides
frontend container health and backend readiness
mocked browser tests vs full-stack browser acceptance
resume/JD browser-storage prohibition
```

- [x] **Step 2: Run all fast checks**

```powershell
mvn test
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend run test
npm --prefix frontend run build
npm --prefix frontend run test:e2e
```

Expected: all commands PASS.

- [x] **Step 3: Run infrastructure-backed checks**

```powershell
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'
mvn verify
npm --prefix frontend run test:e2e:full-stack
docker compose --env-file .env.example config --quiet
```

Expected: all unit, integration, backend E2E, deterministic browser, and full-stack browser tests PASS.

- [x] **Step 4: Perform visual QA in the in-app browser**

Start the real full stack on free ports and inspect at least:

```text
1440x900: dashboard, history, submission, running, report, retryable failure
390x844: same critical route set
```

Verify no overlap, horizontal page overflow, clipped long text, blank content, layout shift, or missing focus state. Save final screenshots outside tracked source unless documentation explicitly needs one.

- [x] **Step 5: Check privacy and repository hygiene**

```powershell
rg -n "real token|Bearer sk-|BEGIN PRIVATE|真实简历" . -g '!target/**' -g '!frontend/node_modules/**' -g '!frontend/dist/**'
git diff --check
git status --short
```

Expected: no secrets or real resume/JD content, no whitespace errors, only intended files changed.

- [x] **Step 6: Commit documentation and close the plan**

```powershell
git add README.md docs frontend src docker-compose.yml docker-compose.e2e.yml .env.example e2e
git commit -m "docs: document frontend product workflow"
```

- [x] **Step 7: Request final code review and integrate**

Perform one combined final review, resolve confirmed important findings with focused tests, rerun the final verification commands, then fast-forward the verified branch into `master` and remove the temporary worktree without touching unrelated user files.

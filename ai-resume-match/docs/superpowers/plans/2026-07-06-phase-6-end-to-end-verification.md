# Phase 6 End-to-End Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Docker-backed end-to-end verification for the real upload, task creation, outbox, RabbitMQ worker, mock AI, report query, and Redis cache flow.

**Architecture:** Keep production code unchanged unless the new integration tests expose a bug. Run a full Spring Boot application with Testcontainers MySQL, Redis, and RabbitMQ, plus a JDK `HttpServer` mock for the OpenAI-compatible endpoint. Generate minimal PDF and DOCX bytes inside integration test support code instead of committing binary fixture files.

**Tech Stack:** Spring Boot 3.3, Java 21, Maven Failsafe, Testcontainers MySQL/RabbitMQ/GenericContainer Redis, JUnit 5, AssertJ, Awaitility, PDFBox, Apache POI, JDK HttpServer.

---

## Covered Scope

- Add Redis Testcontainers coverage through the full application context.
- Add a mock OpenAI-compatible HTTP server for integration tests.
- Add generated minimal PDF and DOCX fixture bytes.
- Add an end-to-end Failsafe test that exercises:
  - `POST /api/resumes`
  - `POST /api/jobs`
  - `POST /api/analysis`
  - scheduled outbox publishing
  - RabbitMQ listener consumption
  - `RunAnalysisUseCase`
  - mock AI HTTP call
  - `GET /api/analysis/{taskId}`
  - `GET /api/analysis/{taskId}/report`
  - Redis report cache write
  - Actuator metrics endpoint availability
- Update docs to mark Phase 6 complete and describe `mvn verify` as the full acceptance command.

## Out of Scope

- No real AI provider calls.
- No frontend, accounts, RBAC, or external vector database.
- No report parser redesign; the mock AI response uses the current parser contract.
- No Docker Compose full smoke unless final verification reveals a gap; Failsafe/Testcontainers are the Phase 6 target.

---

## File Map

- Modify `pom.xml`: add explicit `org.testcontainers:testcontainers` test dependency for Redis `GenericContainer` usage.
- Create `src/integration-test/java/com/zhulikang/aimatch/MockAiServer.java`: small OpenAI-compatible HTTP test server.
- Create `src/integration-test/java/com/zhulikang/aimatch/IntegrationDocumentFixtures.java`: generated PDF/DOCX bytes.
- Create `src/integration-test/java/com/zhulikang/aimatch/EndToEndAnalysisFlowIT.java`: full HTTP analysis flow integration test.
- Modify `README.md`: state that `mvn verify` includes end-to-end Testcontainers flow.
- Modify `docs/development.md`: mark Phase 6 complete and say the engineering-hardening refactor is complete for this plan.
- Modify `docs/architecture.md`: move Redis/e2e verification from pending to implemented reliability coverage.
- Modify `docs/operations/runbook.md`: add the end-to-end verification command to the troubleshooting checklist.

---

## Task 1: Test Dependency and Support Utilities

**Files:**
- Modify: `pom.xml`
- Create: `src/integration-test/java/com/zhulikang/aimatch/MockAiServer.java`
- Create: `src/integration-test/java/com/zhulikang/aimatch/IntegrationDocumentFixtures.java`

- [x] **Step 1: Add explicit Testcontainers core dependency**

Add this dependency after the existing Testcontainers JUnit dependency:

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <scope>test</scope>
</dependency>
```

- [x] **Step 2: Create `MockAiServer`**

Create a package-private integration test helper with:

```java
package com.zhulikang.aimatch;

final class MockAiServer implements AutoCloseable {
    static MockAiServer start(String reportContent) {
        // Start a loopback JDK HttpServer on a random port.
        // Return OpenAI-compatible JSON:
        // {"choices":[{"message":{"content":reportContent}}]}
    }

    String endpoint() {
        // Return http://127.0.0.1:<port>/v1/chat/completions
    }

    int requestCount() {
        // Return the number of AI requests received.
    }

    String lastAuthorization() {
        // Return the latest Authorization header.
    }

    String lastBody() {
        // Return the latest request body.
    }

    @Override
    public void close() {
        // Stop server and executor.
    }
}
```

The implementation must:

- Use only JDK classes and Jackson already available in the project.
- Reject non-POST requests with `405`.
- Set `Content-Type: application/json`.
- Store request count, latest `Authorization`, and latest body for assertions.

- [x] **Step 3: Create `IntegrationDocumentFixtures`**

Create a package-private helper with:

```java
package com.zhulikang.aimatch;

final class IntegrationDocumentFixtures {
    static byte[] pdf(String text) {
        // Use PDFBox to create a one-page PDF containing text.
    }

    static byte[] docx(String text) {
        // Use Apache POI to create a DOCX containing one paragraph.
    }
}
```

Both helpers must return non-empty byte arrays and keep all fixture content synthetic.

- [x] **Step 4: Compile the new support code**

Run:

```powershell
mvn "-DskipTests" test-compile
```

Expected: PASS.

- [x] **Step 5: Commit**

```powershell
git add pom.xml src/integration-test/java/com/zhulikang/aimatch/MockAiServer.java src/integration-test/java/com/zhulikang/aimatch/IntegrationDocumentFixtures.java
git commit -m "test: add end-to-end integration fixtures"
```

---

## Task 2: Full End-to-End Analysis Flow IT

**Files:**
- Create: `src/integration-test/java/com/zhulikang/aimatch/EndToEndAnalysisFlowIT.java`

- [ ] **Step 1: Write the end-to-end integration test**

Create a Spring Boot integration test with:

```java
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndAnalysisFlowIT {
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

    static final String REPORT_CONTENT = "\u5339\u914d\u5206\u6570: 91\nSummary: integration flow succeeded";
    static final MockAiServer aiServer = MockAiServer.start(REPORT_CONTENT);

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
        registry.add("api.token", () -> "test-token");
        registry.add("ai.api-key", () -> "test-ai-key");
        registry.add("ai.endpoint", aiServer::endpoint);
        registry.add("ai.model", () -> "test-model");
        registry.add("analysis.outbox.fixed-delay-ms", () -> "100");
        registry.add("analysis.outbox.confirm-timeout", () -> "10s");
        registry.add("analysis.retry.scheduler-fixed-delay-ms", () -> "60000");
    }
}
```

Add two tests:

```java
@Test
void processesDocxResumeThroughHttpOutboxWorkerAiAndRedisCache() throws Exception {
    FlowResult result = runFlow(
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
        "resume.pdf",
        "application/pdf",
        IntegrationDocumentFixtures.pdf("Java Spring Boot Redis RabbitMQ integration candidate")
    );

    assertThat(result.matchScore()).isEqualTo(91);
    assertThat(aiServer.requestCount()).isEqualTo(before + 1);
    assertThat(aiServer.lastAuthorization()).isEqualTo("Bearer test-ai-key");

    ResponseEntity<String> metrics = restTemplate.getForEntity(
        "/actuator/metrics/analysis.tasks.succeeded",
        String.class
    );
    assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
}
```

Implement `runFlow` to:

1. Upload the resume through multipart HTTP with `X-API-Token`, `X-Request-Id`, and `X-Correlation-Id`.
2. Create a JD with JSON body.
3. Create an analysis task.
4. Await `GET /api/analysis/{taskId}/report` until it returns `200`.
5. Assert `GET /api/analysis/{taskId}` eventually reports `SUCCESS`.
6. Return a record containing `taskId` and `matchScore`.

- [ ] **Step 2: Run the new integration test**

Run:

```powershell
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'; mvn "-Dit.test=EndToEndAnalysisFlowIT" verify
```

Expected: PASS after support code is complete. If it fails, fix the production or test code according to the observed failure and rerun this focused command.

- [ ] **Step 3: Commit**

```powershell
git add src/integration-test/java/com/zhulikang/aimatch/EndToEndAnalysisFlowIT.java
git commit -m "test: add end-to-end analysis flow"
```

---

## Task 3: Documentation Updates

**Files:**
- Modify: `README.md`
- Modify: `docs/development.md`
- Modify: `docs/architecture.md`
- Modify: `docs/operations/runbook.md`

- [ ] **Step 1: Update README**

Ensure README says:

- `mvn test` runs fast unit and slice tests.
- `mvn verify` runs MySQL/RabbitMQ/Redis Testcontainers integration tests and the mock-AI end-to-end flow.
- Docker must be running for `mvn verify`.

- [ ] **Step 2: Update development guide**

Change Phase 6 from "next" to complete. Add that the B+ engineering-hardening refactor acceptance is now `mvn test`, Docker-backed `mvn verify`, and compose config validation.

- [ ] **Step 3: Update architecture docs**

Move "Redis Testcontainers and end-to-end analysis flow verification" from pending to implemented reliability coverage.

- [ ] **Step 4: Update runbook**

Add:

```powershell
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'
mvn verify
```

as the local end-to-end verification command before release or merge.

- [ ] **Step 5: Verify docs**

Run:

```powershell
rg -n "Phase 6|mvn verify|End-to-End|Testcontainers|Redis" README.md docs
git diff --check
```

Expected: wording reflects Phase 6 completion, with no whitespace errors.

- [ ] **Step 6: Commit**

```powershell
git add README.md docs/development.md docs/architecture.md docs/operations/runbook.md
git commit -m "docs: document end-to-end verification"
```

---

## Task 4: Final Verification, Review, and Merge

**Files:**
- No code files unless verification or review reveals a bug.

- [ ] **Step 1: Run fast tests**

```powershell
mvn test
```

Expected: 107+ tests pass.

- [ ] **Step 2: Run full integration verification**

```powershell
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'; mvn verify
```

Expected: fast tests plus all `*IT` integration tests pass.

- [ ] **Step 3: Validate compose and whitespace**

```powershell
docker compose --env-file .env.example config --quiet
git diff --check
```

Expected: PASS/no output except acceptable line-ending warnings.

- [ ] **Step 4: Review**

Review locally or with a subagent when available:

- The new IT does not call a real AI provider.
- The new IT uses MySQL, Redis, and RabbitMQ Testcontainers.
- The new IT exercises HTTP APIs instead of only calling use cases directly.
- The mock AI response contains only synthetic fixture text.
- Redis is tested as cache-aside, not as source of truth.
- Docs match current commands and phase status.

- [ ] **Step 5: Merge back to master**

After all checks pass:

```powershell
cd C:\Users\chen\Documents\New project 2
git merge --ff-only codex/phase-6-end-to-end-verification
cd C:\Users\chen\Documents\New project 2\ai-resume-match
mvn test
```

If master verification passes, remove the worktree and branch:

```powershell
cd C:\Users\chen\Documents\New project 2
git worktree remove .worktrees\phase-6-end-to-end-verification
git branch -d codex/phase-6-end-to-end-verification
```

---

## Acceptance Checklist

- `mvn test` passes.
- Docker-backed `mvn verify` passes.
- `docker compose --env-file .env.example config --quiet` passes.
- A full HTTP DOCX analysis flow reaches `SUCCESS`, calls mock AI, writes a report, and writes Redis cache.
- A full HTTP PDF analysis flow reaches `SUCCESS`, calls mock AI, and exposes analysis success metrics through Actuator.
- Integration tests use MySQL, Redis, and RabbitMQ Testcontainers.
- No real AI endpoint or secret is used by tests.
- README, development guide, architecture docs, and runbook describe current Phase 6 behavior.

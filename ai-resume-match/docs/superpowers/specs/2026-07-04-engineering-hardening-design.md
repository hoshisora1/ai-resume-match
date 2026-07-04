# AI Resume Match Engineering Hardening Design

**Date:** 2026-07-04
**Status:** Approved for design documentation
**Target level:** B+ small-team internal beta with room for refactoring
**Project:** `ai-resume-match`

## 1. Context

The current project already has a working backend prototype for resume and job-description matching. It supports PDF/DOCX resume upload, JD submission, asynchronous analysis through RabbitMQ, local RAG context building, OpenAI-compatible completion, MySQL persistence, Redis report cache-aside, token-protected APIs, and 33 passing tests.

The current shape is good enough for a local demo, but not yet strong enough for a small team to use with real data. The main gaps are production-safe configuration, schema migration, reliable message delivery, clearer module boundaries, observable task execution, stronger data protection, realistic integration tests, and documentation that matches the real implementation.

This design upgrades the project into a small-team internal beta service. It intentionally allows meaningful refactoring, but avoids a full public SaaS platform in this phase.

## 2. Goals

- Preserve the existing core workflow: upload resume, create JD, create analysis task, run async matching, query report.
- Refactor the backend into clear application, domain, infrastructure, API, RAG, and observability boundaries.
- Make MySQL the single source of truth for tasks, reports, resumes, jobs, outbox events, and retry state.
- Add reliable asynchronous delivery with an outbox publisher, idempotent worker behavior, visible retry state, and operational recovery paths.
- Replace production `ddl-auto:update` with Flyway-managed schema migrations.
- Add dev/test/prod configuration profiles with all secrets supplied through environment variables.
- Add Docker-based deployment for app, MySQL, Redis, and RabbitMQ with health checks and persistent volumes.
- Add Actuator health and metrics, correlation IDs, and structured task logs.
- Expand test coverage from mostly unit and slice tests to include Testcontainers integration and end-to-end analysis flow.
- Document API contracts, runtime operations, failure modes, and inner architecture accurately.

## 3. Non-Goals

- No full user account system, RBAC, billing, organization management, or multi-tenant data isolation in this phase.
- No public internet production compliance program in this phase.
- No external vector database requirement in this phase; local hashing retrieval remains acceptable as an internal-beta baseline.
- No frontend application in this phase.
- No replacement of the OpenAI-compatible provider abstraction with a provider-specific SDK unless it is required for reliability.

## 4. Current-State Findings

The current implementation has several strong foundations:

- REST endpoints exist for `/api/resumes`, `/api/jobs`, `/api/analysis`, and `/api/analysis/{taskId}/report`.
- `AnalysisService` publishes RabbitMQ messages after transaction commit.
- `AnalysisWorker` consumes only `taskId`, loads source data from MySQL, builds RAG context, calls AI, and saves reports.
- `match_report.task_id` is unique, which supports idempotent report generation.
- Redis is used as cache-aside for successful report reads, not as the task source of truth.
- Unit and slice tests pass with `mvn test`.

The key engineering gaps are:

- `application.yml` contains local defaults such as `root/root`, `guest/guest`, and `ddl-auto:update`.
- `docker-compose.yml` starts only MySQL, Redis, and RabbitMQ; the app itself is not containerized.
- No Flyway or explicit schema migration exists.
- RabbitMQ DLQ is declared, but worker business failures are caught and acknowledged, so those failures do not enter DLQ.
- If task creation commits but RabbitMQ publish fails, a `PENDING` task can be left without a corresponding message.
- The worker stores only coarse task status and logs only limited failure context.
- AI report parsing depends on a free-text regex for `匹配分数`.
- File upload validation is minimal and mostly based on filename suffix.
- Tests mock Redis and RabbitMQ and use H2 for persistence, so real infrastructure behavior is not verified.
- README and the old implementation plan are useful but do not describe the current engineering contract in enough detail.

## 5. Target Architecture

The refactor should keep the Spring Boot monolith but make module boundaries explicit.

Recommended package layout:

```text
com.zhulikang.aimatch
  api
    auth
    dto
    error
    web
  application
    analysis
    job
    resume
    report
  domain
    analysis
    job
    resume
    report
  infrastructure
    ai
    db
    mq
    redis
    document
    config
  rag
    chunking
    embedding
    retrieval
    prompt
    report
  observability
```

Responsibilities:

- `api`: HTTP routing, request validation, response DTOs, auth headers, exception mapping.
- `application`: use-case orchestration such as upload resume, create job, create analysis, run analysis, retry analysis, and fetch report.
- `domain`: entities, value objects, state transitions, failure categories, and business invariants.
- `infrastructure`: JPA repositories, RabbitMQ publisher/listener adapters, Redis cache, AI HTTP client, document parser adapter, configuration properties.
- `rag`: text chunking, embedding, retrieval, prompt building, AI report schema parsing.
- `observability`: correlation ID filter, task log fields, metrics registration, health contributors.

Controller methods should call use cases and avoid direct repository writes. The RabbitMQ listener should call `RunAnalysisUseCase` and avoid embedding the full analysis flow directly in the listener method.

## 6. Domain Model

Core tables:

- `resume`
  - `id`
  - `file_name`
  - `content_type`
  - `file_size_bytes`
  - `content_hash`
  - `raw_text`
  - `structured_summary`
  - `created_at`
  - `deleted_at`

- `job_description`
  - `id`
  - `content`
  - `skill_tags`
  - `created_at`
  - `deleted_at`

- `analysis_task`
  - `id`
  - `resume_id`
  - `job_description_id`
  - `status`
  - `attempt_count`
  - `max_attempts`
  - `failure_code`
  - `failure_message`
  - `next_retry_at`
  - `started_at`
  - `completed_at`
  - `created_at`
  - `updated_at`

- `match_report`
  - `id`
  - `task_id`
  - `match_score`
  - `report_markdown`
  - `report_json`
  - `created_at`

- `analysis_outbox`
  - `id`
  - `event_type`
  - `aggregate_type`
  - `aggregate_id`
  - `payload_json`
  - `status`
  - `attempt_count`
  - `next_attempt_at`
  - `last_error`
  - `created_at`
  - `published_at`

Task statuses:

```text
PENDING
RUNNING
SUCCESS
FAILED_RETRYABLE
FAILED_FINAL
CANCELLED
```

Valid transitions:

- `PENDING -> RUNNING`
- `RUNNING -> SUCCESS`
- `RUNNING -> FAILED_RETRYABLE`
- `RUNNING -> FAILED_FINAL`
- `FAILED_RETRYABLE -> PENDING`
- `PENDING -> CANCELLED`
- `FAILED_RETRYABLE -> CANCELLED`

`SUCCESS`, `FAILED_FINAL`, and `CANCELLED` are terminal. Re-running terminal tasks requires creating a new analysis task, which preserves auditability.

## 7. API Design

The existing endpoints remain, but the contract becomes explicit.

Existing endpoints:

- `POST /api/resumes`
  - Multipart field: `file`
  - Response: `{ "resumeId": 1 }`

- `POST /api/jobs`
  - Body: `{ "content": "..." }`
  - Response: `{ "jobDescriptionId": 1 }`

- `POST /api/analysis`
  - Body: `{ "resumeId": 1, "jobDescriptionId": 2 }`
  - Response: `{ "taskId": 1, "status": "PENDING" }`

- `GET /api/analysis/{taskId}/report`
  - `200` when report exists
  - `404` when task or report does not exist

New operational endpoints:

- `GET /api/analysis/{taskId}`
  - Returns task status, attempts, failure code, and timestamps.

- `POST /api/analysis/{taskId}/retry`
  - Retries only `FAILED_RETRYABLE` tasks.
  - Returns the refreshed task state.

- `GET /api/health/ready`
  - Delegates to Actuator readiness for platform use.

Error response shape:

```json
{
  "code": "RESUME_FILE_TOO_LARGE",
  "message": "Resume file exceeds the configured maximum size.",
  "requestId": "..."
}
```

Authentication remains API-key based for this phase, but it should be moved behind a dedicated auth filter with these properties:

- Header remains `X-API-Token` for compatibility.
- Token is read only from environment-backed configuration.
- Comparison uses constant-time comparison.
- Auth failures return structured `401` responses.
- The design leaves room to add per-client API keys later without changing business use cases.

## 8. Asynchronous Reliability

The asynchronous chain should be based on outbox plus worker idempotency.

Create-analysis flow:

1. API validates resume and job IDs.
2. `CreateAnalysisTaskUseCase` writes `analysis_task(PENDING)`.
3. The same transaction writes `analysis_outbox(ANALYSIS_REQUESTED)`.
4. An outbox publisher publishes pending events to RabbitMQ.
5. On successful publish, the outbox row is marked `PUBLISHED`.

Outbox publisher:

- Runs on a short fixed delay.
- Uses bounded batch size.
- Uses retry with `attempt_count`, `next_attempt_at`, and `last_error`.
- Publishes message body with only `taskId` and metadata such as event ID and correlation ID.

Worker flow:

1. Consume message.
2. Load task by `taskId`.
3. Atomically claim task if it is `PENDING` or stale `RUNNING`.
4. If already `SUCCESS`, acknowledge and stop.
5. Build RAG input from MySQL source data.
6. Call AI client.
7. Parse report.
8. Save report and mark task `SUCCESS` in one transaction.
9. On failure, classify error and update task status.

Failure classification:

- Retryable: AI timeout, AI 5xx, network failure, temporary Redis/Rabbit/MySQL connectivity failure.
- Final: missing task data, empty extracted resume text, unsupported document, invalid JD input, AI report structure invalid after allowed attempts.

RabbitMQ DLQ remains useful for malformed messages, listener infrastructure errors, and messages that exceed broker-level delivery policy. Business failures are recorded in MySQL so operators can inspect and retry them.

## 9. RAG and AI Report Contract

The current local hashing retrieval can remain for internal beta. The refactor should make it replaceable.

Pipeline:

1. `DocumentTextExtractor` extracts normalized text.
2. `TextChunker` chunks resume text.
3. `EmbeddingClient` embeds chunks and query.
4. `VectorRetriever` returns top K chunks.
5. `PromptBuilder` builds prompt with JD, skill tags, and retrieved chunks.
6. `AiClient` returns completion text.
7. `ReportParser` parses structured JSON first and text fallback second.

Preferred AI output:

```json
{
  "matchScore": 88,
  "matchedSkills": ["Java", "Redis"],
  "missingSkills": ["Kafka"],
  "projectSuggestions": ["..."],
  "interviewQuestions": ["..."],
  "summary": "..."
}
```

The stored report should include:

- `report_json`: canonical structured result for future UI and analytics.
- `report_markdown`: human-readable report for current API consumers.
- `match_score`: indexed numeric score for filtering and statistics.

The parser clamps score to `0..100`, validates required fields, and returns an explicit failure code when parsing fails.

## 10. Configuration and Deployment

Profiles:

- `dev`: local Docker Compose dependencies, verbose logs, relaxed CORS if needed.
- `test`: Testcontainers or H2 for fast tests depending on test phase.
- `prod`: no schema auto-update, no embedded defaults, strict secret requirements.

Configuration rules:

- `spring.jpa.hibernate.ddl-auto=validate` in prod.
- Database, Redis, RabbitMQ, API token, and AI credentials come from environment variables.
- App startup fails fast when required prod secrets are blank.
- Timeouts and retry limits are configurable.

Deployment files:

- `Dockerfile`
  - Multi-stage Maven build.
  - Runtime image with Java 21.
  - Non-root user.
  - Healthcheck endpoint.

- `docker-compose.yml`
  - App service.
  - MySQL with volume and healthcheck.
  - Redis with password and healthcheck.
  - RabbitMQ with management plugin, configured credentials, volume, and healthcheck.
  - Internal network.
  - Minimal host port exposure for local dev.

Flyway:

- Add `src/main/resources/db/migration/V1__initial_schema.sql`.
- Production starts with migration and validation.
- README documents migration expectations and rollback policy.

## 11. Data Security

Internal beta still handles sensitive resume data, so the project needs explicit controls.

Upload protections:

- Max file size configured through `spring.servlet.multipart.max-file-size`.
- Allowed extensions: `.pdf`, `.docx`.
- Validate content type and parser compatibility.
- Reject empty extracted text.
- Avoid loading arbitrarily large files into memory without size checks.

Data handling:

- Store original extracted text only as long as needed for report generation and audit.
- Add `deleted_at` for soft deletion.
- Avoid logging raw resume text, JD content, or AI prompt.
- Log IDs, hashes, sizes, and failure codes instead.
- Document that resume/JD content is sent to the configured AI provider.

Secret handling:

- No real secrets in repository.
- `.env.example` documents variables without values.
- README uses example-only values and warns against committing `.env`.

## 12. Observability

Actuator:

- Enable health, readiness, liveness, and metrics.
- Include health indicators for MySQL, Redis, RabbitMQ, and AI endpoint reachability where practical.

Logging:

- Add request ID filter.
- Add correlation ID to outbox events and RabbitMQ message headers.
- Log task lifecycle events with consistent fields:
  - `requestId`
  - `taskId`
  - `resumeId`
  - `jobDescriptionId`
  - `status`
  - `attempt`
  - `failureCode`
  - `durationMs`

Metrics:

- Analysis tasks created.
- Analysis tasks succeeded.
- Analysis tasks failed by failure code.
- Retry count.
- Worker processing duration.
- AI call duration and failure count.
- Redis report-cache hits and misses.
- Outbox pending and failed counts.
- RabbitMQ queue depth through broker metrics or documented dashboard.

## 13. Testing Strategy

Keep fast tests and add confidence where the current system is weakest.

Fast tests under `mvn test`:

- Controller success and failure response bodies.
- Request validation and structured error codes.
- Domain state transitions.
- Report parser score boundaries and missing fields.
- AI client response parsing and exception mapping.
- RAG prompt and retrieval behavior.

Integration tests under `mvn verify`:

- Flyway migrations against MySQL Testcontainers.
- Redis cache TTL and serialization behavior.
- RabbitMQ exchange, queue, binding, listener, and DLQ configuration.
- Outbox publisher publishes and marks events.
- Worker idempotency on duplicate messages.
- End-to-end flow with MySQL, Redis, RabbitMQ, and mock AI HTTP server.

Document fixtures:

- Minimal valid PDF fixture.
- Minimal valid DOCX fixture.
- Unsupported extension fixture.
- Empty text fixture.

Build quality:

- Add Maven Failsafe for integration tests.
- Add JaCoCo coverage report after the first integration baseline is stable.
- Keep `mvn test` fast enough for frequent local runs.

## 14. Documentation Updates

Update README with:

- Project purpose and architecture summary.
- Local startup using Docker Compose.
- Environment variable table.
- API examples with curl.
- Error response examples.
- Task status and retry semantics.
- AI provider configuration.
- Data handling notice.
- Test commands: `mvn test` and `mvn verify`.

Add operational docs:

- `docs/operations/runbook.md`
  - Startup.
  - Health checks.
  - Common failures.
  - How to inspect failed tasks.
  - How to retry tasks.
  - How to inspect outbox backlog.
  - How to inspect RabbitMQ DLQ.

- `docs/architecture.md`
  - Module boundaries.
  - Runtime topology.
  - Data flow.
  - Task state machine.

The old `docs/superpowers/plans/2026-05-12-rag-resume-job-match.md` should remain as historical implementation context, but README should point to the new design and operations docs for current behavior.

## 15. Implementation Phases

### Phase 1: Safety Net and Contracts

- Add explicit API DTOs and response-body assertions.
- Add report parser tests.
- Add file upload validation tests.
- Add task status endpoint contract.
- Keep behavior compatible with existing API clients.

### Phase 2: Domain and Application Refactor

- Introduce use-case classes for resume, job, analysis, report, and retry operations.
- Move worker orchestration into `RunAnalysisUseCase`.
- Introduce explicit task state transition methods.
- Add failure codes and attempts.

### Phase 3: Persistence and Reliable Messaging

- Add Flyway and initial schema.
- Add outbox table and publisher.
- Change task creation to write outbox events.
- Add worker idempotency and retry scheduling.
- Add RabbitMQ integration tests.

### Phase 4: Deployment and Configuration

- Add Dockerfile.
- Expand Docker Compose to include app service, health checks, volumes, and credentials.
- Split dev/test/prod profiles.
- Add `.env.example`.
- Update README startup flow.

### Phase 5: Observability and Operations

- Add Actuator.
- Add correlation ID.
- Add structured task lifecycle logs.
- Add metrics for tasks, AI, cache, and outbox.
- Add operations runbook.

### Phase 6: End-to-End Verification

- Add Testcontainers for MySQL, Redis, and RabbitMQ.
- Add mock AI HTTP server for integration tests.
- Add end-to-end analysis flow test.
- Add PDF/DOCX fixtures.
- Run `mvn test` and `mvn verify` as final acceptance.

## 16. Acceptance Criteria

The refactor is complete when:

- `mvn test` passes fast unit and slice tests.
- `mvn verify` passes integration and end-to-end tests.
- The app runs through Docker Compose with app, MySQL, Redis, and RabbitMQ.
- Flyway manages schema creation and prod uses schema validation rather than Hibernate update.
- Creating analysis tasks remains backward compatible at the API level.
- A RabbitMQ publish outage cannot permanently strand a task without an outbox record.
- Duplicate worker messages do not create duplicate reports.
- Failed analysis tasks expose status, attempts, failure code, and retry path.
- Report querying uses Redis cache-aside and falls back to MySQL safely.
- Health/readiness endpoints reflect database, Redis, and RabbitMQ availability.
- Logs and metrics are sufficient to diagnose task failure without logging raw resume text or prompt content.
- README and operations docs describe the current system accurately.

## 17. Risks and Mitigations

- Risk: Refactoring module boundaries could break currently working behavior.
  - Mitigation: Add API and worker regression tests before moving orchestration code.

- Risk: Flyway initial schema can drift from current JPA entities.
  - Mitigation: Add MySQL Testcontainers migration tests and use `ddl-auto=validate`.

- Risk: Outbox adds operational complexity.
  - Mitigation: Keep event type count small, expose outbox backlog metrics, and document recovery commands.

- Risk: Structured AI output may be inconsistent.
  - Mitigation: Use JSON-first parsing, text fallback, explicit failure codes, and retry limits.

- Risk: Testcontainers increases build time.
  - Mitigation: Keep integration tests under `mvn verify`, while `mvn test` stays fast.

## 18. Design Decision

Proceed with the B+ engineering-hardening refactor. The system remains a Spring Boot monolith, but gains stronger internal boundaries, reliable async execution, production-safe configuration, observable operations, data handling controls, and realistic tests. This gives the project enough engineering depth for small-team internal beta usage without prematurely building a full public SaaS platform.

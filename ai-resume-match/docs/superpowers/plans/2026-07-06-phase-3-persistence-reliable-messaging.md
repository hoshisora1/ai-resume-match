# Phase 3 Persistence and Reliable Messaging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Flyway-managed schema, an analysis outbox, reliable RabbitMQ publishing, automatic retry scheduling, and integration verification while keeping `mvn test` fast.

**Architecture:** Keep the current Spring Boot monolith and Phase 2 package boundaries. Add outbox persistence beside `analysis` entities, keep use cases writing publish intent through `AnalysisTaskPublisher`, and move direct RabbitMQ sends into an outbox publisher. Fast tests continue to use H2 and Hibernate `create-drop`; real MySQL/RabbitMQ validation runs under Failsafe with `mvn verify`.

**Tech Stack:** Spring Boot 3.3, Java 21, Spring Data JPA, Flyway, RabbitMQ, MySQL, H2 fast tests, Testcontainers integration tests, Maven Surefire/Failsafe.

---

## Covered Scope

- Flyway dependency and `V1__initial_schema.sql` for current tables plus `analysis_outbox`.
- Production/default JPA mode switches from `ddl-auto:update` to `validate`.
- `AnalysisTaskPublisher` writes outbox rows instead of publishing RabbitMQ directly.
- `AnalysisOutboxPublisher` publishes due events to RabbitMQ and marks success/failure without deleting rows.
- Retryable task failures set `nextRetryAt`; exhausted attempts become `FAILED_FINAL`.
- `AnalysisRetryScheduler` moves due `FAILED_RETRYABLE` tasks back to `PENDING` and writes outbox events.
- Integration test entrypoint `mvn verify` covers Flyway migration and outbox publisher behavior with Testcontainers where available.
- Architecture/development/runbook docs reflect reliable messaging and retry behavior.

## Out of Scope

- Full external observability/metrics, Actuator, Docker app service, `.env.example`, and end-to-end PDF/DOCX flow remain later phases.
- No external vector database.
- No accounts, RBAC, frontend, billing, or multi-tenancy.

---

## File Map

- Modify `pom.xml`: add Flyway, Testcontainers, Failsafe, and Spring Boot Maven plugin stays.
- Modify `src/main/resources/application.yml`: use `ddl-auto: validate`, enable Flyway, add outbox/retry scheduler properties, use `utf8mb4` MySQL connection.
- Modify `src/test/resources/application.yml`: keep fast tests on H2 `create-drop`, disable Flyway and scheduling where not explicitly tested.
- Create `src/main/resources/db/migration/V1__initial_schema.sql`: MySQL schema for `resume`, `job_description`, `analysis_task`, `match_report`, `analysis_outbox`.
- Create `src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxEvent.java`: JPA entity for outbox rows.
- Create `src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxEventType.java`: enum with `ANALYSIS_REQUESTED`.
- Create `src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxStatus.java`: enum with `PENDING`, `PROCESSING`, `PUBLISHED`, `FAILED`.
- Create `src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxRepository.java`: due-event query and guarded status updates.
- Modify `src/main/java/com/zhulikang/aimatch/application/analysis/AnalysisTaskPublisher.java`: persist outbox intent.
- Create `src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxPublisher.java`: scheduled/batch publisher to RabbitMQ.
- Modify `src/main/java/com/zhulikang/aimatch/analysis/AnalysisTask.java`: set retry metadata and support automatic retry transition if needed.
- Modify `src/main/java/com/zhulikang/aimatch/analysis/AnalysisTaskRepository.java`: update failure with `nextRetryAt`, query due retry tasks, guarded retry transition.
- Modify `src/main/java/com/zhulikang/aimatch/analysis/AnalysisTaskService.java`: classify retryable failure exhaustion and expose retry scheduling operation.
- Create `src/main/java/com/zhulikang/aimatch/analysis/AnalysisRetryScheduler.java`: scheduled retry scanner.
- Modify tests under `src/test/java/...`: update use-case tests and add outbox/retry tests.
- Create integration tests under `src/integration-test/java/...`: Flyway and outbox/RabbitMQ verification.
- Modify `docs/architecture.md`, `docs/development.md`, `docs/operations/runbook.md`: Phase 3 current behavior.

---

## Task 1: Flyway Schema and Integration Test Entry

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application.yml`
- Create: `src/main/resources/db/migration/V1__initial_schema.sql`
- Create: `src/integration-test/java/com/zhulikang/aimatch/FlywayMigrationIT.java`

- [x] **Step 1: Add failing migration verification test**

Create `src/integration-test/java/com/zhulikang/aimatch/FlywayMigrationIT.java`:

```java
package com.zhulikang.aimatch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class FlywayMigrationIT {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("ai_resume_match")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.autoconfigure.exclude", () -> String.join(",",
            "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
        ));
        registry.add("api.token", () -> "test-token");
        registry.add("ai.api-key", () -> "test-key");
        registry.add("ai.endpoint", () -> "http://localhost/mock");
    }

    @Test
    void migratesCoreTablesAndOutbox(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(tableExists(connection, "analysis_task")).isTrue();
            assertThat(tableExists(connection, "match_report")).isTrue();
            assertThat(tableExists(connection, "analysis_outbox")).isTrue();
            assertThat(indexExists(connection, "analysis_outbox", "idx_analysis_outbox_due")).isTrue();
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws Exception {
        try (ResultSet rows = connection.getMetaData().getTables(null, null, tableName, null)) {
            return rows.next();
        }
    }

    private boolean indexExists(Connection connection, String tableName, String indexName) throws Exception {
        try (ResultSet rows = connection.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
            while (rows.next()) {
                if (indexName.equals(rows.getString("INDEX_NAME"))) {
                    return true;
                }
            }
            return false;
        }
    }
}
```

- [x] **Step 2: Run the integration test and verify it fails**

Run:

```powershell
mvn "-Dit.test=FlywayMigrationIT" verify
```

Expected: FAIL because Failsafe/Testcontainers/Flyway and migration file are not yet configured.

- [x] **Step 3: Add dependencies and Failsafe configuration**

Update `pom.xml` with:

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>rabbitmq</artifactId>
    <scope>test</scope>
</dependency>
```

Add Maven plugins:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
    <version>3.2.5</version>
    <configuration>
        <testSourceDirectory>${project.basedir}/src/integration-test/java</testSourceDirectory>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>integration-test</goal>
                <goal>verify</goal>
            </goals>
        </execution>
    </executions>
</plugin>
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <testSource>${java.version}</testSource>
        <testTarget>${java.version}</testTarget>
    </configuration>
</plugin>
```

- [x] **Step 4: Add migration and config**

Set main `application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/ai_resume_match?useUnicode=true&characterEncoding=utf8mb4&connectionCollation=utf8mb4_unicode_ci&serverTimezone=Asia/Shanghai
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
```

Set test `application.yml`:

```yaml
spring:
  flyway:
    enabled: false
  task:
    scheduling:
      enabled: false
```

Create `V1__initial_schema.sql` with tables and indexes listed in the File Map. Use `varchar` for enums and `longtext` for text fields. Do not use MySQL native enum.

- [x] **Step 5: Verify**

Run:

```powershell
mvn "-Dit.test=FlywayMigrationIT" verify
mvn test
```

Expected: `FlywayMigrationIT` passes with MySQL Testcontainers; fast tests remain passing.

- [x] **Step 6: Commit**

```powershell
git add pom.xml src/main/resources/application.yml src/test/resources/application.yml src/main/resources/db/migration/V1__initial_schema.sql src/integration-test/java/com/zhulikang/aimatch/FlywayMigrationIT.java
git commit -m "feat: add flyway schema baseline"
```

---

## Task 2: Outbox Model and Publish Intent

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxEvent.java`
- Create: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxEventType.java`
- Create: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxStatus.java`
- Create: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxRepository.java`
- Modify: `src/main/java/com/zhulikang/aimatch/application/analysis/AnalysisTaskPublisher.java`
- Modify: `src/test/java/com/zhulikang/aimatch/application/analysis/CreateAnalysisTaskUseCaseTest.java`
- Modify: `src/test/java/com/zhulikang/aimatch/application/analysis/RetryAnalysisTaskUseCaseTest.java`
- Add/modify repository tests in `src/test/java/com/zhulikang/aimatch/analysis/DomainRepositoryTest.java`

- [x] **Step 1: Write failing outbox tests**

Add tests that assert:

```java
assertThat(outboxRepository.findAll())
    .singleElement()
    .satisfies(event -> {
        assertThat(event.getEventType()).isEqualTo(AnalysisOutboxEventType.ANALYSIS_REQUESTED);
        assertThat(event.getAggregateType()).isEqualTo("analysis_task");
        assertThat(event.getAggregateId()).isEqualTo(task.getId());
        assertThat(event.getStatus()).isEqualTo(AnalysisOutboxStatus.PENDING);
        assertThat(event.getPayloadJson()).contains("\"taskId\":" + task.getId());
    });
```

Update use-case unit tests to mock `AnalysisOutboxRepository` instead of `RabbitTemplate` expectations.

- [x] **Step 2: Run failing tests**

Run:

```powershell
mvn "-Dtest=CreateAnalysisTaskUseCaseTest,RetryAnalysisTaskUseCaseTest,DomainRepositoryTest" test
```

Expected: FAIL because outbox classes do not exist and publisher still requires `RabbitTemplate`.

- [x] **Step 3: Implement outbox entity/repository**

Implement `AnalysisOutboxEvent` with fields:

```text
id, eventType, aggregateType, aggregateId, payloadJson, status,
attemptCount, nextAttemptAt, lastError, createdAt, publishedAt
```

Implement factory:

```java
public static AnalysisOutboxEvent analysisRequested(Long taskId) {
    return new AnalysisOutboxEvent(
        AnalysisOutboxEventType.ANALYSIS_REQUESTED,
        "analysis_task",
        taskId,
        "{\"taskId\":" + taskId + "}"
    );
}
```

Implement methods:

```java
public void markPublished(LocalDateTime now)
public void markPublishFailed(String lastError, LocalDateTime nextAttemptAt)
```

- [x] **Step 4: Change `AnalysisTaskPublisher` to persist intent**

Replace direct RabbitTemplate publishing with:

```java
public void publishAfterCommit(Long taskId) {
    outboxRepository.save(AnalysisOutboxEvent.analysisRequested(taskId));
}
```

Keep the method name for API compatibility, but document in code by naming the dependency clearly; do not register transaction synchronization.

- [x] **Step 5: Verify**

Run:

```powershell
mvn "-Dtest=CreateAnalysisTaskUseCaseTest,RetryAnalysisTaskUseCaseTest,DomainRepositoryTest" test
mvn test
```

Expected: fast tests pass.

- [x] **Step 6: Commit**

```powershell
git add src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutbox*.java src/main/java/com/zhulikang/aimatch/application/analysis/AnalysisTaskPublisher.java src/test/java/com/zhulikang/aimatch/application/analysis/CreateAnalysisTaskUseCaseTest.java src/test/java/com/zhulikang/aimatch/application/analysis/RetryAnalysisTaskUseCaseTest.java src/test/java/com/zhulikang/aimatch/analysis/DomainRepositoryTest.java
git commit -m "feat: persist analysis publish requests in outbox"
```

---

## Task 3: Outbox Publisher

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxPublisher.java`
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxRepository.java`
- Create: `src/test/java/com/zhulikang/aimatch/analysis/AnalysisOutboxPublisherTest.java`
- Create: `src/integration-test/java/com/zhulikang/aimatch/analysis/AnalysisOutboxPublisherIT.java`

- [x] **Step 1: Write failing unit tests**

Test success:

```java
publisher.publishPending();
verify(rabbitTemplate).convertAndSend(RabbitConfig.ANALYSIS_EXCHANGE, RabbitConfig.ANALYSIS_ROUTING_KEY, 99L);
assertThat(event.getStatus()).isEqualTo(AnalysisOutboxStatus.PUBLISHED);
```

Test failure:

```java
doThrow(new AmqpException("down")).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Long.class));
publisher.publishPending();
assertThat(event.getStatus()).isEqualTo(AnalysisOutboxStatus.FAILED);
assertThat(event.getAttemptCount()).isEqualTo(1);
assertThat(event.getLastError()).contains("down");
assertThat(event.getNextAttemptAt()).isAfter(now);
```

- [x] **Step 2: Run failing tests**

```powershell
mvn "-Dtest=AnalysisOutboxPublisherTest" test
```

Expected: FAIL because publisher does not exist.

- [x] **Step 3: Implement publisher**

`AnalysisOutboxPublisher` should:

- Use `@Scheduled(fixedDelayString = "${analysis.outbox.fixed-delay-ms:5000}")`.
- Read `analysis.outbox.batch-size`, default `20`.
- Fetch due `PENDING`, `FAILED`, or expired `PROCESSING` rows with `nextAttemptAt <= now`.
- Publish `taskId` as a `Long`.
- Claim rows with a guarded transition to `PROCESSING` before publish.
- Mark rows `PUBLISHED` only after RabbitMQ publisher confirm succeeds and the message is not returned.
- Mark rows `FAILED`, increment attempts, set `lastError`, set `nextAttemptAt` on publish exception, broker nack, confirm timeout, or returned/unroutable message.

- [x] **Step 4: Add integration verification**

`AnalysisOutboxPublisherIT` should use MySQL and RabbitMQ Testcontainers and assert outbox rows are read from MySQL, published to `analysis.queue`, and persisted as `PUBLISHED`; it should also cover returned/unroutable messages becoming `FAILED`.

Run:

```powershell
mvn "-Dit.test=AnalysisOutboxPublisherIT" verify
```

Expected: PASS when RabbitMQ container is available.

- [x] **Step 5: Verify**

```powershell
mvn "-Dtest=AnalysisOutboxPublisherTest" test
mvn "-Dit.test=AnalysisOutboxPublisherIT" verify
mvn test
```

- [x] **Step 6: Commit**

```powershell
git add src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxPublisher.java src/main/java/com/zhulikang/aimatch/analysis/AnalysisOutboxRepository.java src/test/java/com/zhulikang/aimatch/analysis/AnalysisOutboxPublisherTest.java src/integration-test/java/com/zhulikang/aimatch/analysis/AnalysisOutboxPublisherIT.java
git commit -m "feat: publish analysis outbox events"
```

---

## Task 4: Automatic Retry Scheduling

**Files:**
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisTask.java`
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisTaskRepository.java`
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisTaskService.java`
- Create: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisRetryScheduler.java`
- Modify: `src/test/java/com/zhulikang/aimatch/analysis/AnalysisTaskTest.java`
- Modify: `src/test/java/com/zhulikang/aimatch/analysis/AnalysisTaskServiceTest.java`
- Create: `src/test/java/com/zhulikang/aimatch/analysis/AnalysisRetrySchedulerTest.java`
- Modify: `src/test/java/com/zhulikang/aimatch/analysis/DomainRepositoryTest.java`

- [x] **Step 1: Write failing tests for retry metadata**

Assert retryable failure below max attempts sets `FAILED_RETRYABLE` and `nextRetryAt`.

Assert failure at max attempts becomes `FAILED_FINAL` and clears `nextRetryAt`.

Assert due scheduler writes outbox and moves task to `PENDING`.

- [x] **Step 2: Run failing tests**

```powershell
mvn "-Dtest=AnalysisTaskTest,AnalysisTaskServiceTest,AnalysisRetrySchedulerTest,DomainRepositoryTest" test
```

Expected: FAIL because `nextRetryAt` and scheduler behavior do not exist.

- [x] **Step 3: Implement retry scheduling**

Use default properties:

```yaml
analysis:
  retry:
    delay: 1m
    scheduler-fixed-delay-ms: 30000
    batch-size: 20
```

Repository additions:

```java
List<AnalysisTask> findTop20ByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(...);
int markRetryableAsPending(...);
```

Service behavior:

- `markRetryableFailure` reads current task attempt count.
- If attempts are exhausted, call `markFinalFailure`.
- Otherwise write `FAILED_RETRYABLE` and `nextRetryAt = now + retryDelay`.

Scheduler behavior:

- For each due task, guard-transition it to `PENDING`.
- On successful transition, call `AnalysisTaskPublisher.publishAfterCommit(taskId)` to write outbox in the same transaction.

- [x] **Step 4: Verify**

```powershell
mvn "-Dtest=AnalysisTaskTest,AnalysisTaskServiceTest,AnalysisRetrySchedulerTest,DomainRepositoryTest" test
mvn test
```

- [x] **Step 5: Commit**

```powershell
git add src/main/java/com/zhulikang/aimatch/analysis/AnalysisTask.java src/main/java/com/zhulikang/aimatch/analysis/AnalysisTaskRepository.java src/main/java/com/zhulikang/aimatch/analysis/AnalysisTaskService.java src/main/java/com/zhulikang/aimatch/analysis/AnalysisRetryScheduler.java src/test/java/com/zhulikang/aimatch/analysis/AnalysisTaskTest.java src/test/java/com/zhulikang/aimatch/analysis/AnalysisTaskServiceTest.java src/test/java/com/zhulikang/aimatch/analysis/AnalysisRetrySchedulerTest.java src/test/java/com/zhulikang/aimatch/analysis/DomainRepositoryTest.java
git commit -m "feat: schedule retryable analysis tasks"
```

---

## Task 5: Worker Idempotency, Docs, and Final Verification

**Files:**
- Modify: `src/main/java/com/zhulikang/aimatch/application/analysis/RunAnalysisUseCase.java`
- Modify: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisTaskService.java`
- Modify: `docs/architecture.md`
- Modify: `docs/development.md`
- Modify: `docs/operations/runbook.md`
- Modify tests around worker idempotency.

- [x] **Step 1: Write/adjust idempotency tests**

Cover:

- Duplicate message for `SUCCESS` task is skipped and does not write another report.
- Fresh `RUNNING` task is skipped even when RabbitMQ marks the message `redelivered=true`.
- Stale `RUNNING` task can be reclaimed.

- [x] **Step 2: Run failing or focused tests**

```powershell
mvn "-Dtest=RunAnalysisUseCaseTest,AnalysisTaskServiceTest,DomainRepositoryTest" test
```

Expected: any missing idempotency behavior fails before implementation.

- [x] **Step 3: Implement minimal fixes**

Only adjust worker/task-service semantics needed by the failing tests. Keep message body ID-only and avoid adding frontend/API changes.

- [x] **Step 4: Update docs**

Update docs to say:

- Phase 3 adds Flyway, outbox, automatic retry scheduling, and `mvn verify`.
- Manual retry still exists, but automatic retry handles due `FAILED_RETRYABLE` tasks.
- Outbox backlog is inspectable in `analysis_outbox`.

- [x] **Step 5: Final review and verification**

Run:

```powershell
mvn test
mvn verify
git diff --check
```

Dispatch a final read-only review agent with the Phase 3 plan and branch diff.

- [x] **Step 6: Commit**

```powershell
git add src/main/java src/test/java src/integration-test/java docs
git commit -m "docs: update phase 3 reliable messaging guidance"
```

---

## Acceptance Checklist

- `mvn test` passes fast tests.
- `mvn verify` passes integration tests when Docker/Testcontainers is available.
- Flyway creates and validates core schema plus `analysis_outbox`.
- Main app no longer relies on Hibernate `ddl-auto:update`.
- Creating and retrying analysis tasks writes outbox events in the same transaction.
- Outbox publisher retries failed publishes without losing events.
- Retryable failures expose `nextRetryAt`; exhausted attempts become `FAILED_FINAL`.
- Automatic retry scheduler republishes due retryable tasks through outbox.
- Worker idempotency behavior remains protected by tests.
- Docs describe the new Phase 3 runtime and operations accurately.

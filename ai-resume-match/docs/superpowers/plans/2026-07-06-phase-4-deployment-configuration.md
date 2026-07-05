# Phase 4 Deployment and Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `ai-resume-match` runnable as a containerized app with Docker Compose, environment-backed profiles, documented local startup, and deployment configuration checks.

**Architecture:** Keep the Spring Boot monolith and current package boundaries. Add profile-specific configuration for local development, Docker Compose runtime, and production; keep secrets outside source control and validate deployment files with lightweight tests. Add a minimal Actuator health endpoint only to support container health checks; metrics and observability instrumentation remain Phase 5.

**Tech Stack:** Spring Boot 3.3, Java 21, Maven, Flyway, MySQL 8.4, Redis 7, RabbitMQ 3 management image, Docker Compose, JUnit 5.

---

## Covered Scope

- Add `Dockerfile` with multi-stage Maven build, Java 21 runtime, non-root user, and healthcheck.
- Add `.dockerignore` to keep build context small and avoid copying target/worktrees/git metadata.
- Expand `docker-compose.yml` to run the app plus MySQL, Redis, and RabbitMQ with health checks, persistent volumes, service names, and environment-backed credentials.
- Add `.env.example` with example-only values for every required compose variable.
- Split runtime config into common, `dev`, `docker`, and `prod` profiles.
- Add minimal Actuator health exposure for Docker health checks; do not add custom metrics or request ID yet.
- Update README and runbook to describe Maven-local and full Docker Compose startup.
- Keep `mvn test` fast and keep `mvn verify` as the integration entrypoint.

## Out of Scope

- No frontend, accounts, RBAC, or external vector database.
- No structured request logs, correlation ID, metrics dashboards, or custom health contributors; those remain Phase 5.
- No end-to-end AI mock flow or PDF/DOCX fixture flow; those remain Phase 6.

---

## File Map

- Modify `pom.xml`: add `spring-boot-starter-actuator`.
- Modify `src/main/resources/application.yml`: keep common settings and set `spring.profiles.default=dev`.
- Create `src/main/resources/application-dev.yml`: local Maven defaults for MySQL/Redis/RabbitMQ and dev placeholder token/key.
- Create `src/main/resources/application-docker.yml`: Docker service-name configuration using env vars.
- Create `src/main/resources/application-prod.yml`: production env-only configuration with no local credential defaults.
- Create `src/test/java/com/zhulikang/aimatch/config/DeploymentConfigurationTest.java`: file-level contract tests for profiles, compose, Dockerfile, `.env.example`, and `.dockerignore`.
- Create `Dockerfile`: multi-stage app image.
- Create `.dockerignore`: Docker build exclusions.
- Modify `docker-compose.yml`: app service, dependency health checks, volumes, credentials.
- Create `.env.example`: example-only compose environment.
- Modify `README.md`: startup, env vars, API examples, tests.
- Modify `docs/operations/runbook.md`: Docker Compose startup, health checks, compose troubleshooting.
- Modify `docs/development.md`: mark Phase 4 current behavior after implementation.

---

## Task 1: Profile Contract Tests

**Files:**
- Create: `src/test/java/com/zhulikang/aimatch/config/DeploymentConfigurationTest.java`

- [ ] **Step 1: Write failing profile tests**

Create `src/test/java/com/zhulikang/aimatch/config/DeploymentConfigurationTest.java` with:

```java
package com.zhulikang.aimatch.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentConfigurationTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();

    @Test
    void commonConfigurationUsesDevAsDefaultProfileAndExposesHealthProbes() {
        Properties properties = yaml("src/main/resources/application.yml");

        assertThat(properties.getProperty("spring.profiles.default")).isEqualTo("dev");
        assertThat(properties.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(properties.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(properties.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(properties.getProperty("management.endpoints.web.exposure.include")).contains("health");
    }

    @Test
    void devProfileKeepsLocalDefaultsOnlyInDev() {
        Properties properties = yaml("src/main/resources/application-dev.yml");

        assertThat(properties.getProperty("spring.datasource.url")).contains("localhost:3306");
        assertThat(properties.getProperty("spring.datasource.username")).contains("root");
        assertThat(properties.getProperty("spring.rabbitmq.username")).contains("guest");
        assertThat(properties.getProperty("api.token")).isEqualTo("${API_TOKEN:dev-token}");
        assertThat(properties.getProperty("ai.api-key")).isEqualTo("${AI_API_KEY:dev-ai-key}");
    }

    @Test
    void dockerProfileUsesComposeServiceNamesAndEnvironmentBackedSecrets() {
        Properties properties = yaml("src/main/resources/application-docker.yml");

        assertThat(properties.getProperty("spring.datasource.url")).contains("mysql:3306");
        assertThat(properties.getProperty("spring.datasource.username")).isEqualTo("${MYSQL_USER}");
        assertThat(properties.getProperty("spring.datasource.password")).isEqualTo("${MYSQL_PASSWORD}");
        assertThat(properties.getProperty("spring.data.redis.host")).isEqualTo("redis");
        assertThat(properties.getProperty("spring.data.redis.password")).isEqualTo("${REDIS_PASSWORD}");
        assertThat(properties.getProperty("spring.rabbitmq.host")).isEqualTo("rabbitmq");
        assertThat(properties.getProperty("spring.rabbitmq.username")).isEqualTo("${RABBITMQ_DEFAULT_USER}");
        assertThat(properties.getProperty("spring.rabbitmq.password")).isEqualTo("${RABBITMQ_DEFAULT_PASS}");
        assertThat(properties.getProperty("api.token")).isEqualTo("${API_TOKEN}");
        assertThat(properties.getProperty("ai.api-key")).isEqualTo("${AI_API_KEY}");
    }

    @Test
    void prodProfileDoesNotContainLocalCredentialDefaults() throws IOException {
        String content = read("src/main/resources/application-prod.yml");

        assertThat(content).doesNotContain("localhost");
        assertThat(content).doesNotContain("root");
        assertThat(content).doesNotContain("guest");
        assertThat(content).doesNotContain("dev-token");
        assertThat(content).doesNotContain("dev-ai-key");
        assertThat(content).contains("${API_TOKEN}");
        assertThat(content).contains("${AI_API_KEY}");
    }

    @Test
    void envExampleDocumentsRequiredComposeVariables() throws IOException {
        String content = read(".env.example");

        assertThat(content).contains("APP_PORT=8080");
        assertThat(content).contains("MYSQL_DATABASE=ai_resume_match");
        assertThat(content).contains("MYSQL_USER=ai_match");
        assertThat(content).contains("MYSQL_PASSWORD=dev-mysql-password");
        assertThat(content).contains("REDIS_PASSWORD=dev-redis-password");
        assertThat(content).contains("RABBITMQ_DEFAULT_USER=ai_match");
        assertThat(content).contains("RABBITMQ_DEFAULT_PASS=dev-rabbit-password");
        assertThat(content).contains("API_TOKEN=dev-token");
        assertThat(content).contains("AI_API_KEY=replace-with-your-dev-key");
    }

    @Test
    void dockerAssetsDeclareAppServiceHealthcheckAndSmallBuildContext() throws IOException {
        String compose = read("docker-compose.yml");
        String dockerfile = read("Dockerfile");
        String dockerignore = read(".dockerignore");

        assertThat(compose).contains("app:");
        assertThat(compose).contains("condition: service_healthy");
        assertThat(compose).contains("mysql-data:");
        assertThat(compose).contains("redis-data:");
        assertThat(compose).contains("rabbitmq-data:");
        assertThat(dockerfile).contains("FROM maven:");
        assertThat(dockerfile).contains("FROM eclipse-temurin:21-jre");
        assertThat(dockerfile).contains("USER app");
        assertThat(dockerfile).contains("HEALTHCHECK");
        assertThat(dockerfile).contains("/actuator/health/readiness");
        assertThat(dockerignore).contains("target/");
        assertThat(dockerignore).contains(".git/");
        assertThat(dockerignore).contains(".worktrees/");
    }

    private static Properties yaml(String relativePath) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new FileSystemResource(ROOT.resolve(relativePath)));
        Properties properties = factory.getObject();
        assertThat(properties).isNotNull();
        return properties;
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(ROOT.resolve(relativePath), StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
mvn "-Dtest=DeploymentConfigurationTest" test
```

Expected: FAIL because `application-dev.yml`, `application-docker.yml`, `application-prod.yml`, `Dockerfile`, `.dockerignore`, and `.env.example` do not exist, and Actuator health settings are not present.

---

## Task 2: Profiles and Health Endpoint Support

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/resources/application-dev.yml`
- Create: `src/main/resources/application-docker.yml`
- Create: `src/main/resources/application-prod.yml`

- [ ] **Step 1: Add Actuator dependency**

Add to `pom.xml` dependencies:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

- [ ] **Step 2: Move common settings into `application.yml`**

Update `src/main/resources/application.yml` to:

```yaml
spring:
  application:
    name: ai-resume-match
  profiles:
    default: dev
  servlet:
    multipart:
      max-file-size: 5MB
      max-request-size: 5MB
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
  rabbitmq:
    publisher-confirm-type: correlated
    publisher-returns: true
    template:
      mandatory: true

ai:
  endpoint: ${AI_ENDPOINT:https://api.openai.com/v1/chat/completions}
  api-key: ${AI_API_KEY}
  model: ${AI_MODEL:gpt-4o-mini}

api:
  token: ${API_TOKEN}

report:
  cache-ttl: ${REPORT_CACHE_TTL:10m}

analysis:
  running-timeout: ${ANALYSIS_RUNNING_TIMEOUT:15m}
  outbox:
    fixed-delay-ms: ${ANALYSIS_OUTBOX_FIXED_DELAY_MS:5000}
    batch-size: ${ANALYSIS_OUTBOX_BATCH_SIZE:20}
    retry-delay: ${ANALYSIS_OUTBOX_RETRY_DELAY:30s}
    confirm-timeout: ${ANALYSIS_OUTBOX_CONFIRM_TIMEOUT:5s}
  retry:
    delay: ${ANALYSIS_RETRY_DELAY:1m}
    scheduler-fixed-delay-ms: ${ANALYSIS_RETRY_SCHEDULER_FIXED_DELAY_MS:30000}
    batch-size: ${ANALYSIS_RETRY_BATCH_SIZE:20}

management:
  endpoint:
    health:
      probes:
        enabled: true
  endpoints:
    web:
      exposure:
        include: health,info
```

- [ ] **Step 3: Create `application-dev.yml`**

Create `src/main/resources/application-dev.yml`:

```yaml
spring:
  datasource:
    url: ${MYSQL_URL:jdbc:mysql://localhost:3306/ai_resume_match?useUnicode=true&characterEncoding=utf8mb4&connectionCollation=utf8mb4_unicode_ci&serverTimezone=Asia/Shanghai}
    username: ${MYSQL_USER:root}
    password: ${MYSQL_PASSWORD:root}
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:guest}
    password: ${RABBITMQ_PASSWORD:guest}

ai:
  api-key: ${AI_API_KEY:dev-ai-key}

api:
  token: ${API_TOKEN:dev-token}
```

- [ ] **Step 4: Create `application-docker.yml`**

Create `src/main/resources/application-docker.yml`:

```yaml
spring:
  datasource:
    url: jdbc:mysql://mysql:3306/${MYSQL_DATABASE}?useUnicode=true&characterEncoding=utf8mb4&connectionCollation=utf8mb4_unicode_ci&serverTimezone=Asia/Shanghai
    username: ${MYSQL_USER}
    password: ${MYSQL_PASSWORD}
  data:
    redis:
      host: redis
      port: 6379
      password: ${REDIS_PASSWORD}
  rabbitmq:
    host: rabbitmq
    port: 5672
    username: ${RABBITMQ_DEFAULT_USER}
    password: ${RABBITMQ_DEFAULT_PASS}

api:
  token: ${API_TOKEN}

ai:
  api-key: ${AI_API_KEY}
```

- [ ] **Step 5: Create `application-prod.yml`**

Create `src/main/resources/application-prod.yml`:

```yaml
spring:
  datasource:
    url: ${MYSQL_URL}
    username: ${MYSQL_USER}
    password: ${MYSQL_PASSWORD}
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD}
  rabbitmq:
    host: ${RABBITMQ_HOST}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME}
    password: ${RABBITMQ_PASSWORD}

api:
  token: ${API_TOKEN}

ai:
  api-key: ${AI_API_KEY}
```

- [ ] **Step 6: Verify profile tests still fail only on Docker assets**

Run:

```powershell
mvn "-Dtest=DeploymentConfigurationTest" test
```

Expected: FAIL only because `Dockerfile`, `.dockerignore`, `.env.example`, and compose app service are not implemented.

- [ ] **Step 7: Commit**

```powershell
git add pom.xml src/main/resources/application.yml src/main/resources/application-dev.yml src/main/resources/application-docker.yml src/main/resources/application-prod.yml src/test/java/com/zhulikang/aimatch/config/DeploymentConfigurationTest.java
git commit -m "feat: add deployment profile configuration"
```

---

## Task 3: Docker Image and Compose Runtime

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`
- Create: `.env.example`
- Modify: `docker-compose.yml`

- [ ] **Step 1: Create `.dockerignore`**

Create `.dockerignore`:

```dockerignore
target/
.git/
.worktrees/
.idea/
*.iml
.env
```

- [ ] **Step 2: Create `Dockerfile`**

Create `Dockerfile`:

```dockerfile
# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --gid app --home-dir /app app

WORKDIR /app
COPY --from=build /workspace/target/ai-resume-match-0.1.0.jar /app/app.jar
RUN chown -R app:app /app

USER app
EXPOSE 8080

ENV JAVA_OPTS=""
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
```

- [ ] **Step 3: Create `.env.example`**

Create `.env.example`:

```dotenv
APP_PORT=8080

MYSQL_DATABASE=ai_resume_match
MYSQL_ROOT_PASSWORD=dev-root-password
MYSQL_USER=ai_match
MYSQL_PASSWORD=dev-mysql-password

REDIS_PASSWORD=dev-redis-password

RABBITMQ_DEFAULT_USER=ai_match
RABBITMQ_DEFAULT_PASS=dev-rabbit-password

API_TOKEN=dev-token
AI_API_KEY=replace-with-your-dev-key
AI_ENDPOINT=https://api.openai.com/v1/chat/completions
AI_MODEL=gpt-4o-mini

REPORT_CACHE_TTL=10m
ANALYSIS_RUNNING_TIMEOUT=15m
ANALYSIS_OUTBOX_FIXED_DELAY_MS=5000
ANALYSIS_OUTBOX_BATCH_SIZE=20
ANALYSIS_OUTBOX_RETRY_DELAY=30s
ANALYSIS_OUTBOX_CONFIRM_TIMEOUT=5s
ANALYSIS_RETRY_DELAY=1m
ANALYSIS_RETRY_SCHEDULER_FIXED_DELAY_MS=30000
ANALYSIS_RETRY_BATCH_SIZE=20
```

- [ ] **Step 4: Replace compose with full runtime**

Update `docker-compose.yml`:

```yaml
services:
  app:
    build:
      context: .
    image: ai-resume-match:local
    env_file:
      - .env
    environment:
      SPRING_PROFILES_ACTIVE: docker
      MYSQL_DATABASE: ${MYSQL_DATABASE}
      MYSQL_USER: ${MYSQL_USER}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
      REDIS_PASSWORD: ${REDIS_PASSWORD}
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_DEFAULT_USER}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_DEFAULT_PASS}
      API_TOKEN: ${API_TOKEN}
      AI_API_KEY: ${AI_API_KEY}
      AI_ENDPOINT: ${AI_ENDPOINT}
      AI_MODEL: ${AI_MODEL}
      REPORT_CACHE_TTL: ${REPORT_CACHE_TTL:-10m}
      ANALYSIS_RUNNING_TIMEOUT: ${ANALYSIS_RUNNING_TIMEOUT:-15m}
      ANALYSIS_OUTBOX_FIXED_DELAY_MS: ${ANALYSIS_OUTBOX_FIXED_DELAY_MS:-5000}
      ANALYSIS_OUTBOX_BATCH_SIZE: ${ANALYSIS_OUTBOX_BATCH_SIZE:-20}
      ANALYSIS_OUTBOX_RETRY_DELAY: ${ANALYSIS_OUTBOX_RETRY_DELAY:-30s}
      ANALYSIS_OUTBOX_CONFIRM_TIMEOUT: ${ANALYSIS_OUTBOX_CONFIRM_TIMEOUT:-5s}
      ANALYSIS_RETRY_DELAY: ${ANALYSIS_RETRY_DELAY:-1m}
      ANALYSIS_RETRY_SCHEDULER_FIXED_DELAY_MS: ${ANALYSIS_RETRY_SCHEDULER_FIXED_DELAY_MS:-30000}
      ANALYSIS_RETRY_BATCH_SIZE: ${ANALYSIS_RETRY_BATCH_SIZE:-20}
    ports:
      - "${APP_PORT:-8080}:8080"
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
    restart: unless-stopped

  mysql:
    image: mysql:8.4
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: ${MYSQL_DATABASE}
      MYSQL_USER: ${MYSQL_USER}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
    ports:
      - "3306:3306"
    volumes:
      - mysql-data:/var/lib/mysql
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h 127.0.0.1 -u$${MYSQL_USER} -p$${MYSQL_PASSWORD} --silent"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s
    restart: unless-stopped

  redis:
    image: redis:7
    command: ["redis-server", "--requirepass", "${REDIS_PASSWORD}"]
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD-SHELL", "redis-cli -a \"$${REDIS_PASSWORD}\" ping | grep PONG"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 10s
    restart: unless-stopped

  rabbitmq:
    image: rabbitmq:3.13-management
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_DEFAULT_USER}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_DEFAULT_PASS}
    ports:
      - "5672:5672"
      - "15672:15672"
    volumes:
      - rabbitmq-data:/var/lib/rabbitmq
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 20s
    restart: unless-stopped

volumes:
  mysql-data:
  redis-data:
  rabbitmq-data:
```

- [ ] **Step 5: Verify deployment tests pass**

Run:

```powershell
mvn "-Dtest=DeploymentConfigurationTest" test
```

Expected: PASS.

- [ ] **Step 6: Validate compose rendering**

Run:

```powershell
docker compose --env-file .env.example config
```

Expected: Docker Compose renders app, MySQL, Redis, RabbitMQ, health checks, and volumes without interpolation errors.

- [ ] **Step 7: Commit**

```powershell
git add Dockerfile .dockerignore .env.example docker-compose.yml src/test/java/com/zhulikang/aimatch/config/DeploymentConfigurationTest.java
git commit -m "feat: add docker compose app runtime"
```

---

## Task 4: README and Operations Documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/operations/runbook.md`
- Modify: `docs/development.md`
- Modify: `docs/architecture.md`

- [ ] **Step 1: Update README**

Update README with:

- A short architecture summary.
- Full Docker Compose startup:

```powershell
Copy-Item .env.example .env
notepad .env
docker compose up -d --build
curl.exe -i http://localhost:8080/actuator/health/readiness
```

- Maven-local startup:

```powershell
docker compose up -d mysql redis rabbitmq
$env:SPRING_PROFILES_ACTIVE="dev"
$env:API_TOKEN="dev-token"
$env:AI_API_KEY="replace-with-local-dev-key"
mvn spring-boot:run
```

- Test commands:

```powershell
mvn test
mvn verify
docker compose --env-file .env.example config
```

- Environment variable table covering app, MySQL, Redis, RabbitMQ, API token, and AI provider variables.

- [ ] **Step 2: Update runbook**

Update `docs/operations/runbook.md` to:

- Say Phase 4 includes Dockerfile, compose app service, profile split, `.env.example`, and Actuator health for container readiness.
- Document Docker Compose full startup and dependency-only startup.
- Update MySQL user/password examples to use `.env` values.
- Update Redis checks to include password:

```powershell
docker compose exec redis redis-cli -a $env:REDIS_PASSWORD ping
```

- Update RabbitMQ management login to use `RABBITMQ_DEFAULT_USER` / `RABBITMQ_DEFAULT_PASS`.
- Document app health check:

```powershell
curl.exe -i http://localhost:8080/actuator/health/readiness
```

- [ ] **Step 3: Update development and architecture docs**

Update:

- `docs/development.md`: mark Phase 4 as completed/current and Phase 5 as next.
- `docs/architecture.md`: note Docker Compose runtime includes app, MySQL, Redis, RabbitMQ; profiles are `dev`, `docker`, `prod`.

- [ ] **Step 4: Verify docs and tests**

Run:

```powershell
mvn test
docker compose --env-file .env.example config
rg -n "Phase 4.*后续|Dockerfile 和 app service 的 compose 启动方式|尚未实现" README.md docs
git diff --check
```

Expected: tests pass, compose config renders, no stale Phase 4 wording, no whitespace errors.

- [ ] **Step 5: Commit**

```powershell
git add README.md docs/operations/runbook.md docs/development.md docs/architecture.md
git commit -m "docs: document docker deployment workflow"
```

---

## Task 5: Docker Build Smoke and Final Verification

**Files:**
- No code files unless verification reveals a bug.

- [ ] **Step 1: Build image**

Run:

```powershell
docker build -t ai-resume-match:phase4 .
```

Expected: image builds successfully.

- [ ] **Step 2: Run full verification**

Run:

```powershell
mvn test
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'; mvn verify
docker compose --env-file .env.example config
git diff --check
```

Expected: all commands pass.

- [ ] **Step 3: Optional local compose smoke**

If Docker resources are available and ports `3306`, `6379`, `5672`, `8080`, and `15672` are free, run:

```powershell
Copy-Item .env.example .env -Force
docker compose up -d --build
docker compose ps
curl.exe -i http://localhost:8080/actuator/health/readiness
docker compose down
```

Expected: services become healthy and readiness returns `200`. If a port is already in use, skip this smoke and rely on `docker build`, `docker compose config`, `mvn test`, and `mvn verify`.

- [ ] **Step 4: Final review**

Request a read-only review focused on:

- Secret handling and `.env` safety.
- Profile precedence and accidental dev defaults in `prod`.
- Compose service names, health checks, and persistent volumes.
- README/runbook accuracy.

- [ ] **Step 5: Commit fixes if needed**

If review or verification reveals issues, fix them with focused commits.

---

## Acceptance Checklist

- `mvn test` passes.
- `mvn verify` passes when Docker/Testcontainers is available.
- `docker compose --env-file .env.example config` renders without interpolation errors.
- `docker build -t ai-resume-match:phase4 .` succeeds.
- `Dockerfile` uses Java 21, non-root runtime user, and a healthcheck.
- Compose includes app, MySQL, Redis, RabbitMQ, health checks, volumes, and environment-backed credentials.
- `.env.example` documents required variables with example-only values and `.env` remains ignored.
- `dev`, `docker`, and `prod` profiles exist; production profile has no local credential defaults.
- README and runbook describe the current Docker and Maven-local workflows.

package com.zhulikang.aimatch.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        assertThat(properties.getProperty("management.endpoint.health.group.liveness.include")).isEqualTo("livenessState");
        assertThat(properties.getProperty("management.endpoint.health.group.readiness.include"))
            .isEqualTo("readinessState,db");
        assertThat(properties.getProperty("management.endpoints.web.exposure.include")).contains("health");
        assertThat(properties.getProperty("management.endpoints.web.exposure.include")).contains("metrics");
        assertThat(properties.getProperty("management.endpoints.web.exposure.include")).contains("prometheus");
        assertThat(properties.getProperty("management.metrics.distribution.percentiles-histogram.agent.call.duration"))
            .isEqualTo("true");
        assertThat(properties.getProperty("management.tracing.enabled"))
            .isEqualTo("${TRACING_ENABLED:false}");
        assertThat(properties.getProperty("management.tracing.sampling.probability"))
            .isEqualTo("${TRACING_SAMPLING_PROBABILITY:1.0}");
        assertThat(properties.getProperty("management.tracing.propagation.type")).isEqualTo("W3C");
        assertThat(properties.getProperty("management.otlp.tracing.endpoint"))
            .isEqualTo("${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://localhost:4318/v1/traces}");
        assertThat(properties.getProperty("spring.rabbitmq.listener.simple.observation-enabled"))
            .isEqualTo("true");
        assertThat(properties.getProperty("spring.rabbitmq.template.observation-enabled")).isEqualTo("true");
        assertThat(properties.getProperty("analysis.engine")).isEqualTo("${ANALYSIS_ENGINE:agent}");
        assertThat(properties.getProperty("analysis.scheduling.enabled"))
            .isEqualTo("${ANALYSIS_SCHEDULING_ENABLED:true}");
        assertThat(properties.getProperty("agent.read-timeout")).isEqualTo("${AGENT_READ_TIMEOUT:60s}");
        assertThat(properties.getProperty("analysis.outbox.max-attempts"))
            .isEqualTo("${ANALYSIS_OUTBOX_MAX_ATTEMPTS:10}");
        assertThat(properties.getProperty("analysis.outbox.lease-duration"))
            .isEqualTo("${ANALYSIS_OUTBOX_LEASE_DURATION:30s}");
        assertThat(properties.getProperty("analysis.outbox.retry-max-delay"))
            .isEqualTo("${ANALYSIS_OUTBOX_RETRY_MAX_DELAY:15m}");
        assertThat(properties.getProperty("analysis.outbox.retry-jitter-ratio"))
            .isEqualTo("${ANALYSIS_OUTBOX_RETRY_JITTER_RATIO:0.2}");
        assertThat(properties.getProperty("analysis.retention.outbox"))
            .isEqualTo("${ANALYSIS_OUTBOX_RETENTION:30d}");
        assertThat(properties.getProperty("analysis.retention.idempotency"))
            .isEqualTo("${ANALYSIS_IDEMPOTENCY_RETENTION:30d}");
        assertThat(properties.getProperty("analysis.retention.user-data"))
            .isEqualTo("${ANALYSIS_USER_DATA_RETENTION:30d}");
        assertThat(properties.getProperty("analysis.retention.scheduler-fixed-delay-ms"))
            .isEqualTo("${ANALYSIS_RETENTION_FIXED_DELAY_MS:3600000}");
        assertThat(properties.getProperty("analysis.retention.batch-size"))
            .isEqualTo("${ANALYSIS_RETENTION_BATCH_SIZE:200}");
        assertThat(properties.getProperty("spring.task.scheduling.shutdown.await-termination"))
            .isEqualTo("true");
        assertThat(properties.getProperty(
            "spring.task.scheduling.shutdown.await-termination-period"
        )).isEqualTo("${SCHEDULER_SHUTDOWN_TIMEOUT:10s}");
        assertThat(properties.getProperty("spring.data.redis.connect-timeout"))
            .isEqualTo("${REDIS_CONNECT_TIMEOUT:500ms}");
        assertThat(properties.getProperty("spring.data.redis.timeout"))
            .isEqualTo("${REDIS_COMMAND_TIMEOUT:500ms}");
        assertThat(properties.getProperty("resume.upload.max-file-size"))
            .isEqualTo("${RESUME_MAX_FILE_SIZE:5MB}");
        assertThat(properties.getProperty("resume.upload.max-pdf-pages"))
            .isEqualTo("${RESUME_MAX_PDF_PAGES:50}");
        assertThat(properties.getProperty("resume.upload.max-docx-entries"))
            .isEqualTo("${RESUME_MAX_DOCX_ENTRIES:512}");
        assertThat(properties.getProperty("resume.upload.max-docx-entry-size"))
            .isEqualTo("${RESUME_MAX_DOCX_ENTRY_SIZE:10MB}");
        assertThat(properties.getProperty("resume.upload.max-docx-uncompressed-size"))
            .isEqualTo("${RESUME_MAX_DOCX_UNCOMPRESSED_SIZE:20MB}");
        assertThat(properties.getProperty("logging.pattern.level"))
            .contains("requestId")
            .contains("correlationId");
    }

    @Test
    void docsDescribeObservabilityMetricsAndCorrelationIds() throws IOException {
        String readme = read("README.md");
        String runbook = read("docs/operations/runbook.md");
        String architecture = read("docs/architecture.md");

        assertThat(readme).contains("X-Request-Id").contains("/actuator/metrics");
        assertThat(runbook)
            .contains("analysis.tasks.created")
            .contains("analysis.outbox.backlog")
            .contains("analysis.retention.deleted");
        assertThat(architecture)
            .contains("requestId")
            .contains("correlationId")
            .contains("Micrometer")
            .contains("analysis.retention.deleted");
    }

    @Test
    void devProfileKeepsLocalDefaultsOnlyInDev() {
        Properties properties = yaml("src/main/resources/application-dev.yml");

        assertThat(properties.getProperty("spring.datasource.url")).contains("localhost:3306");
        assertThat(properties.getProperty("spring.datasource.username")).isEqualTo("${MYSQL_USER:ai_match}");
        assertThat(properties.getProperty("spring.datasource.password")).isEqualTo("${MYSQL_PASSWORD:dev-mysql-password}");
        assertThat(properties.getProperty("spring.data.redis.password")).isEqualTo("${REDIS_PASSWORD:dev-redis-password}");
        assertThat(properties.getProperty("spring.rabbitmq.username")).isEqualTo("${RABBITMQ_USERNAME:ai_match}");
        assertThat(properties.getProperty("spring.rabbitmq.password")).isEqualTo("${RABBITMQ_PASSWORD:dev-rabbit-password}");
        assertThat(properties.getProperty("api.token")).isEqualTo("${API_TOKEN:dev-token}");
        assertThat(properties.getProperty("ai.api-key")).isEqualTo("${AI_API_KEY:dev-ai-key}");
    }

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

    @Test
    void bootApplicationLoadsDevDefaultsWhenNoProfileIsActive() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(EmptyConfiguration.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .properties("spring.config.location=file:src/main/resources/")
                .run()) {
            Environment environment = context.getEnvironment();

            assertThat(environment.getProperty("api.token")).isEqualTo("dev-token");
            assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("ai_match");
            assertThat(environment.getProperty("spring.data.redis.password")).isEqualTo("dev-redis-password");
            assertThat(environment.getProperty("spring.rabbitmq.username")).isEqualTo("ai_match");
            assertThat(environment.getProperty("ai.api-key")).isEqualTo("dev-ai-key");
            assertThat(environment.getDefaultProfiles()).contains("dev");
        }
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
        Properties properties = yaml("src/main/resources/application-prod.yml");

        assertThat(content).doesNotContain("localhost");
        assertThat(content).doesNotContain("root");
        assertThat(content).doesNotContain("guest");
        assertThat(content).doesNotContain("dev-token");
        assertThat(content).doesNotContain("dev-ai-key");
        assertThat(content).contains("${API_TOKEN}");
        assertThat(content).contains("${AI_API_KEY}");
        assertThat(content).contains("${AGENT_SERVICE_URL}");
        assertThat(content).contains("${AGENT_SERVICE_TOKEN}");
        assertThat(content).contains("${ANALYSIS_ENGINE}");
        assertThat(properties.getProperty("springdoc.api-docs.enabled"))
            .isEqualTo("${OPENAPI_DOCS_ENABLED:false}");
    }

    @Test
    void envExampleDocumentsRequiredComposeVariables() throws IOException {
        String content = read(".env.example");

        assertThat(content).contains("APP_PORT=8080");
        assertThat(content).contains("FRONTEND_PORT=3000");
        assertThat(content).contains("MYSQL_PORT=3306");
        assertThat(content).contains("REDIS_PORT=6379");
        assertThat(content).contains("RABBITMQ_AMQP_PORT=5672");
        assertThat(content).contains("RABBITMQ_MANAGEMENT_PORT=15672");
        assertThat(content).contains("MYSQL_DATABASE=ai_resume_match");
        assertThat(content).contains("MYSQL_USER=ai_match");
        assertThat(content).contains("MYSQL_PASSWORD=dev-mysql-password");
        assertThat(content).contains("REDIS_PASSWORD=dev-redis-password");
        assertThat(content).contains("RABBITMQ_DEFAULT_USER=ai_match");
        assertThat(content).contains("RABBITMQ_DEFAULT_PASS=dev-rabbit-password");
        assertThat(content).contains("API_TOKEN=dev-token");
        assertThat(content).contains("AI_API_KEY=replace-with-your-dev-key");
        assertThat(content).contains("ANALYSIS_ENGINE=agent");
        assertThat(content).contains("ANALYSIS_SCHEDULING_ENABLED=true");
        assertThat(content).contains("AGENT_MODEL_TIMEOUT_SECONDS=45");
        assertThat(content).contains("AGENT_ANALYSIS_TIMEOUT_SECONDS=50");
        assertThat(content).contains("AGENT_MODEL_PRICING_VERSION=");
        assertThat(content).contains("AGENT_MODEL_INPUT_COST_USD_PER_MILLION_TOKENS=");
        assertThat(content).contains("AGENT_MODEL_OUTPUT_COST_USD_PER_MILLION_TOKENS=");
        assertThat(content).contains("AGENT_READ_TIMEOUT=60s");
        assertThat(content).contains("ANALYSIS_RUNNING_RECOVERY_FIXED_DELAY_MS=30000");
        assertThat(content).contains("ANALYSIS_RUNNING_RECOVERY_BATCH_SIZE=20");
        assertThat(content).contains("ANALYSIS_OUTBOX_MAX_ATTEMPTS=10");
        assertThat(content).contains("ANALYSIS_OUTBOX_LEASE_DURATION=30s");
        assertThat(content).contains("ANALYSIS_OUTBOX_RETRY_MAX_DELAY=15m");
        assertThat(content).contains("ANALYSIS_OUTBOX_RETRY_JITTER_RATIO=0.2");
        assertThat(content).contains("ANALYSIS_OUTBOX_RETENTION=30d");
        assertThat(content).contains("ANALYSIS_IDEMPOTENCY_RETENTION=30d");
        assertThat(content).contains("ANALYSIS_USER_DATA_RETENTION=30d");
        assertThat(content).contains("ANALYSIS_RETENTION_FIXED_DELAY_MS=3600000");
        assertThat(content).contains("ANALYSIS_RETENTION_BATCH_SIZE=200");
        assertThat(content).contains("SCHEDULER_SHUTDOWN_TIMEOUT=10s");
        assertThat(content).contains("RESUME_MAX_FILE_SIZE=5MB");
        assertThat(content).contains("RESUME_MAX_PDF_PAGES=50");
        assertThat(content).contains("RESUME_MAX_DOCX_ENTRIES=512");
        assertThat(content).contains("RESUME_MAX_DOCX_ENTRY_SIZE=10MB");
        assertThat(content).contains("RESUME_MAX_DOCX_UNCOMPRESSED_SIZE=20MB");
        assertThat(content).contains("TRACING_ENABLED=false");
        assertThat(content).contains("AGENT_TRACING_ENABLED=false");
        assertThat(content).contains("TRACING_SAMPLING_PROBABILITY=1.0");
        assertThat(content).contains("OTEL_TRACES_SAMPLER_ARG=1.0");
        assertThat(content).contains("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://tempo:4318/v1/traces");
        assertThat(content).contains("OTEL_SERVICE_NAME=ai-resume-match-agent");
        assertThat(content).contains("TEMPO_PORT=3200");
    }

    @Test
    void defaultCrossServiceTimeoutBudgetIsStrictlyNested() throws IOException {
        Properties defaults = new Properties();
        try (var reader = Files.newBufferedReader(ROOT.resolve(".env.example"), StandardCharsets.UTF_8)) {
            defaults.load(reader);
        }

        int providerTimeoutSeconds = Integer.parseInt(
            defaults.getProperty("AGENT_MODEL_TIMEOUT_SECONDS")
        );
        int analysisTimeoutSeconds = Integer.parseInt(
            defaults.getProperty("AGENT_ANALYSIS_TIMEOUT_SECONDS")
        );
        int javaReadTimeoutSeconds = Integer.parseInt(
            defaults.getProperty("AGENT_READ_TIMEOUT").replaceFirst("s$", "")
        );

        assertThat(providerTimeoutSeconds).isLessThan(analysisTimeoutSeconds);
        assertThat(analysisTimeoutSeconds).isLessThan(javaReadTimeoutSeconds);
    }

    @Test
    void dockerAssetsDeclareAppServiceHealthcheckAndSmallBuildContext() throws IOException {
        String compose = read("docker-compose.yml");
        String dockerfile = read("Dockerfile");
        String dockerignore = read(".dockerignore");
        String gitignore = read(".gitignore");

        assertThat(compose).contains("app:");
        assertThat(compose).contains("condition: service_healthy");
        assertThat(compose).contains("mysql-data:");
        assertThat(compose).contains("redis-data:");
        assertThat(compose).contains("rabbitmq-data:");
        assertThat(compose)
            .contains("ANALYSIS_RUNNING_RECOVERY_FIXED_DELAY_MS: ${ANALYSIS_RUNNING_RECOVERY_FIXED_DELAY_MS:-30000}")
            .contains("ANALYSIS_RUNNING_RECOVERY_BATCH_SIZE: ${ANALYSIS_RUNNING_RECOVERY_BATCH_SIZE:-20}")
            .contains("ANALYSIS_OUTBOX_MAX_ATTEMPTS: ${ANALYSIS_OUTBOX_MAX_ATTEMPTS:-10}")
            .contains("ANALYSIS_OUTBOX_LEASE_DURATION: ${ANALYSIS_OUTBOX_LEASE_DURATION:-30s}")
            .contains("ANALYSIS_OUTBOX_RETRY_MAX_DELAY: ${ANALYSIS_OUTBOX_RETRY_MAX_DELAY:-15m}")
            .contains("ANALYSIS_OUTBOX_RETRY_JITTER_RATIO: ${ANALYSIS_OUTBOX_RETRY_JITTER_RATIO:-0.2}")
            .contains("ANALYSIS_OUTBOX_RETENTION: ${ANALYSIS_OUTBOX_RETENTION:-30d}")
            .contains("ANALYSIS_IDEMPOTENCY_RETENTION: ${ANALYSIS_IDEMPOTENCY_RETENTION:-30d}")
            .contains("ANALYSIS_USER_DATA_RETENTION: ${ANALYSIS_USER_DATA_RETENTION:-30d}")
            .contains("ANALYSIS_RETENTION_FIXED_DELAY_MS: ${ANALYSIS_RETENTION_FIXED_DELAY_MS:-3600000}")
            .contains("ANALYSIS_RETENTION_BATCH_SIZE: ${ANALYSIS_RETENTION_BATCH_SIZE:-200}")
            .contains("SCHEDULER_SHUTDOWN_TIMEOUT: ${SCHEDULER_SHUTDOWN_TIMEOUT:-10s}")
            .contains("RESUME_MAX_FILE_SIZE: ${RESUME_MAX_FILE_SIZE:-5MB}")
            .contains("RESUME_MAX_PDF_PAGES: ${RESUME_MAX_PDF_PAGES:-50}")
            .contains("RESUME_MAX_DOCX_ENTRIES: ${RESUME_MAX_DOCX_ENTRIES:-512}")
            .contains("RESUME_MAX_DOCX_ENTRY_SIZE: ${RESUME_MAX_DOCX_ENTRY_SIZE:-10MB}")
            .contains("RESUME_MAX_DOCX_UNCOMPRESSED_SIZE: ${RESUME_MAX_DOCX_UNCOMPRESSED_SIZE:-20MB}")
            .contains("ANALYSIS_SCHEDULING_ENABLED: ${ANALYSIS_SCHEDULING_ENABLED:-true}")
            .contains("AGENT_READ_TIMEOUT: ${AGENT_READ_TIMEOUT:-60s}")
            .contains("AGENT_MODEL_TIMEOUT_SECONDS: ${AGENT_MODEL_TIMEOUT_SECONDS:-45}")
            .contains("AGENT_ANALYSIS_TIMEOUT_SECONDS: ${AGENT_ANALYSIS_TIMEOUT_SECONDS:-50}")
            .contains("AGENT_MODEL_PRICING_VERSION: ${AGENT_MODEL_PRICING_VERSION:-}")
            .contains("AGENT_MODEL_INPUT_COST_USD_PER_MILLION_TOKENS: ${AGENT_MODEL_INPUT_COST_USD_PER_MILLION_TOKENS:-}")
            .contains("AGENT_MODEL_OUTPUT_COST_USD_PER_MILLION_TOKENS: ${AGENT_MODEL_OUTPUT_COST_USD_PER_MILLION_TOKENS:-}")
            .contains("TRACING_ENABLED: ${TRACING_ENABLED:-false}")
            .contains("AGENT_TRACING_ENABLED: ${AGENT_TRACING_ENABLED:-false}")
            .contains("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:-http://tempo:4318/v1/traces}");
        assertThat(dockerfile).contains("FROM maven:");
        assertThat(dockerfile).contains("FROM eclipse-temurin:21-jre");
        assertThat(dockerfile).contains("COPY --from=build /workspace/target/*.jar /app/app.jar");
        assertThat(dockerfile).contains("USER app");
        assertThat(dockerfile).contains("HEALTHCHECK");
        assertThat(dockerfile).contains("/actuator/health/readiness");
        assertThat(dockerfile).contains("exec java $JAVA_OPTS -jar /app/app.jar");
        assertThat(dockerignore).contains("target/");
        assertThat(dockerignore).contains("**/node_modules/");
        assertThat(dockerignore).contains("**/.venv/");
        assertThat(dockerignore).contains(".git/");
        assertThat(dockerignore).contains(".worktrees/");
        assertThat(gitignore).contains(".env");
        assertThat(gitignore).doesNotContain(".env.example");
    }

    @Test
    void observabilityOverlayProvisionsPrivateMetricsTracingDashboardAndAlerts() throws IOException {
        String compose = read("docker-compose.observability.yml");
        String prometheus = read("observability/prometheus/prometheus.yml");
        String alerts = read("observability/prometheus/alerts.yml");
        String datasource = read("observability/grafana/provisioning/datasources/prometheus.yml");
        String tempoDatasource = read("observability/grafana/provisioning/datasources/tempo.yml");
        String tempo = read("observability/tempo/tempo.yml");
        String dashboards = read("observability/grafana/provisioning/dashboards/dashboards.yml");
        String dashboard = read("observability/grafana/dashboards/ai-resume-match-operations.json");
        JsonNode dashboardJson = new ObjectMapper().readTree(dashboard);

        assertThat(compose)
            .contains("prom/prometheus:v2.55.1")
            .contains("grafana/grafana:11.3.1")
            .contains("grafana/tempo:2.7.2")
            .contains("127.0.0.1:${PROMETHEUS_PORT:-9090}:9090")
            .contains("127.0.0.1:${GRAFANA_PORT:-3001}:3000")
            .contains("127.0.0.1:${TEMPO_PORT:-3200}:3200")
            .contains("TRACING_ENABLED: \"true\"")
            .contains("AGENT_TRACING_ENABLED: \"true\"")
            .contains("http://tempo:4318/v1/traces")
            .contains("GF_AUTH_ANONYMOUS_ENABLED: \"false\"");
        assertThat(prometheus)
            .contains("metrics_path: /actuator/prometheus")
            .contains("app:8080")
            .contains("job_name: ai-resume-match-agent")
            .contains("metrics_path: /metrics")
            .contains("agent:8000")
            .contains("/etc/prometheus/alerts.yml");
        assertThat(alerts)
            .contains("AnalysisOutboxOldestEventTooOld")
            .contains("analysis_outbox_oldest_age_seconds > 120")
            .contains("AnalysisOutboxDeadLetterPresent")
            .contains("AiResumeMatchTargetDown")
            .contains("AiResumeMatchAgentTargetDown")
            .contains("AgentProviderFailureRatioHigh")
            .contains("AgentProtocolErrorsHigh")
            .contains("AgentCostUsageMissing");
        assertThat(alerts).contains("AgentAnalysisCapacityExhausted");
        assertThat(datasource).contains("uid: prometheus").contains("http://prometheus:9090");
        assertThat(tempoDatasource)
            .contains("uid: tempo")
            .contains("http://tempo:3200")
            .contains("datasourceUid: prometheus");
        assertThat(tempo)
            .contains("http_listen_port: 3200")
            .contains("endpoint: 0.0.0.0:4318")
            .contains("block_retention: 24h")
            .contains("backend: local");
        assertThat(dashboards).contains("/var/lib/grafana/dashboards");
        assertThat(dashboard)
            .contains("AI Resume Match — Operations")
            .contains("analysis_outbox_oldest_age_seconds")
            .contains("agent_call_duration_seconds_bucket")
            .contains("agent_service_provider_call_duration_seconds_bucket")
            .contains("agent_service_model_tokens_total")
            .contains("agent_service_model_estimated_cost_usd_total")
            .contains("agent_service_cost_estimates_total")
            .contains("agent_service_retrieval_calls_total")
            .contains("agent_service_tool_calls_total")
            .contains("analysis_tasks_failed_total")
            .contains("agent_service_analysis_capacity_rejections_total");
        assertThat(dashboardJson.path("uid").asText()).isEqualTo("ai-resume-match-ops");
        assertThat(dashboardJson.path("panels")).hasSize(15);
        assertThat(yaml("observability/prometheus/prometheus.yml")).isNotEmpty();
        assertThat(yaml("observability/prometheus/alerts.yml")).isNotEmpty();
        assertThat(yaml("observability/grafana/provisioning/datasources/prometheus.yml")).isNotEmpty();
        assertThat(yaml("observability/grafana/provisioning/datasources/tempo.yml")).isNotEmpty();
        assertThat(yaml("observability/tempo/tempo.yml")).isNotEmpty();
        assertThat(yaml("observability/grafana/provisioning/dashboards/dashboards.yml")).isNotEmpty();
    }

    @Test
    void frontendImageUsesAReproduciblePinnedMultiStageNonRootBuild() throws IOException {
        assertThat(ROOT.resolve("frontend/Dockerfile")).isRegularFile();
        assertThat(ROOT.resolve("frontend/.dockerignore")).isRegularFile();

        String dockerfile = read("frontend/Dockerfile");
        String dockerignore = read("frontend/.dockerignore");

        assertThat(dockerfile)
            .contains("FROM node:24-alpine@sha256:a0b9bf06e4e6193cf7a0f58816cc935ff8c2a908f81e6f1a95432d679c54fbfd AS build")
            .contains("RUN npm ci")
            .contains("RUN npm run build")
            .contains("FROM nginxinc/nginx-unprivileged:1.29-alpine@sha256:0c79d56aee561a1d81c63f00eee5fb5fe29279560cdc55e91425133104c7fbe6")
            .contains("COPY --chmod=755 nginx/frontend-entrypoint.sh /usr/local/bin/frontend-entrypoint.sh")
            .contains("COPY --from=build /workspace/dist /usr/share/nginx/html")
            .contains("ENTRYPOINT [\"/usr/local/bin/frontend-entrypoint.sh\"]")
            .contains("CMD [\"nginx\", \"-g\", \"daemon off;\"]")
            .contains("USER 101")
            .contains("EXPOSE 8080")
            .contains("HEALTHCHECK")
            .contains("/frontend-health");
        assertThat(dockerignore)
            .contains("node_modules/")
            .contains("dist/")
            .contains("reports/")
            .contains(".env")
            .contains("secrets/");
    }

    @Test
    void frontendComposeServiceUsesRuntimeTokenAndConfigurablePorts() throws IOException {
        String compose = read("docker-compose.yml");

        assertThat(compose)
            .contains("frontend:")
            .contains("context: ./frontend")
            .contains("API_TOKEN: ${API_TOKEN}")
            .contains("NGINX_ENVSUBST_FILTER: \"^API_TOKEN$\"")
            .contains("${FRONTEND_PORT:-3000}:8080")
            .contains("${APP_PORT:-8080}:8080")
            .contains("${MYSQL_PORT:-3306}:3306")
            .contains("${REDIS_PORT:-6379}:6379")
            .contains("${RABBITMQ_AMQP_PORT:-5672}:5672")
            .contains("${RABBITMQ_MANAGEMENT_PORT:-15672}:15672");
        int frontendStart = compose.indexOf("  frontend:");
        int frontendEnd = compose.indexOf("\n  mysql:", frontendStart);
        assertThat(compose.substring(frontendStart, frontendEnd))
            .contains("app:")
            .contains("condition: service_healthy")
            .contains("restart: unless-stopped");
    }

    @Test
    void nginxTemplateSecuresAndRoutesFrontendTraffic() throws IOException {
        assertThat(ROOT.resolve("frontend/nginx/default.conf.template")).isRegularFile();
        assertThat(ROOT.resolve("frontend/nginx/frontend-entrypoint.sh")).isRegularFile();

        String nginxTemplate = read("frontend/nginx/default.conf.template");
        String frontendEntrypoint = read("frontend/nginx/frontend-entrypoint.sh");

        assertThat(nginxTemplate)
            .contains("client_max_body_size 6m")
            .contains("geo $api_token_dollar")
            .contains("default \"$\"")
            .contains("location = /frontend-health")
            .contains("location = /backend-health")
            .contains("proxy_pass http://app:8080/actuator/health/readiness")
            .contains("location /api/")
            .contains("proxy_pass http://app:8080")
            .contains("proxy_set_header X-API-Token \"${API_TOKEN_NGINX}\"")
            .doesNotContain("proxy_set_header X-API-Token ${API_TOKEN}")
            .contains("try_files $uri $uri/ /index.html")
            .contains("proxy_connect_timeout")
            .contains("proxy_send_timeout")
            .contains("proxy_read_timeout")
            .contains("Content-Security-Policy")
            .contains("default-src 'self'")
            .contains("script-src 'self'")
            .contains("style-src 'self'")
            .contains("img-src 'self'")
            .contains("font-src 'self'")
            .contains("connect-src 'self'")
            .contains("form-action 'self'")
            .contains("object-src 'none'")
            .contains("base-uri 'self'")
            .contains("frame-ancestors 'none'")
            .contains("X-Content-Type-Options \"nosniff\"")
            .contains("Referrer-Policy \"no-referrer\"")
            .contains("X-Frame-Options \"DENY\"")
            .contains("location = /index.html")
            .contains("expires -1")
            .contains("location ^~ /assets/")
            .contains("expires 1y");
        assertThat(frontendEntrypoint)
            .contains("API_TOKEN_NGINX")
            .contains("${api_token_dollar}")
            .contains("tr -d '\\r\\n'")
            .contains("NGINX_ENVSUBST_FILTER='^API_TOKEN_NGINX$'")
            .contains("unset API_TOKEN")
            .contains("exec /docker-entrypoint.sh \"$@\"");
    }

    @Test
    void nginxContainerRegressionCoversRealRenderingSyntaxAndProxyHeader() throws IOException {
        assertThat(ROOT.resolve("frontend/tests/nginx-container-test.ps1")).isRegularFile();

        String containerTest = read("frontend/tests/nginx-container-test.ps1");

        assertThat(containerTest)
            .contains("task11 token $uri")
            .contains("nginx -t")
            .contains("nginx -T")
            .contains("try_files $uri $uri/ /index.html")
            .contains("proxy_set_header X-Request-Id $http_x_request_id")
            .contains("proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for")
            .contains("X-API-Token")
            .contains("docker logs")
            .contains("API_TOKEN must not contain CR or LF");
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

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {
    }
}

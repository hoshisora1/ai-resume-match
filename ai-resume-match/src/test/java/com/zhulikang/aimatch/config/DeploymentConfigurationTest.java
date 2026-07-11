package com.zhulikang.aimatch.config;

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
        assertThat(properties.getProperty("management.endpoint.health.group.readiness.include")).isEqualTo("readinessState");
        assertThat(properties.getProperty("management.endpoints.web.exposure.include")).contains("health");
        assertThat(properties.getProperty("management.endpoints.web.exposure.include")).contains("metrics");
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
        assertThat(runbook).contains("analysis.tasks.created").contains("analysis.outbox.backlog");
        assertThat(architecture).contains("requestId").contains("correlationId").contains("Micrometer");
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
        assertThat(dockerfile).contains("FROM maven:");
        assertThat(dockerfile).contains("FROM eclipse-temurin:21-jre");
        assertThat(dockerfile).contains("COPY --from=build /workspace/target/*.jar /app/app.jar");
        assertThat(dockerfile).contains("USER app");
        assertThat(dockerfile).contains("HEALTHCHECK");
        assertThat(dockerfile).contains("/actuator/health/readiness");
        assertThat(dockerfile).contains("exec java $JAVA_OPTS -jar /app/app.jar");
        assertThat(dockerignore).contains("target/");
        assertThat(dockerignore).contains(".git/");
        assertThat(dockerignore).contains(".worktrees/");
        assertThat(gitignore).contains(".env");
        assertThat(gitignore).doesNotContain(".env.example");
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
            .contains("proxy_set_header X-Original-URI $request_uri")
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
            .contains("proxy_set_header X-Original-URI $request_uri")
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

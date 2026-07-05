package com.zhulikang.aimatch.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

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
        String gitignore = read(".gitignore");

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
        assertThat(gitignore).contains(".env");
        assertThat(gitignore).doesNotContain(".env.example");
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

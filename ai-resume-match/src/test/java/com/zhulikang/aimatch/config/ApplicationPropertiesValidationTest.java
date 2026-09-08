package com.zhulikang.aimatch.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationPropertiesValidationTest {
    private static final String[] VALID_PROPERTIES = {
        "ai.endpoint=https://example.test/v1/chat/completions",
        "ai.api-key=test-ai-key",
        "ai.model=test-model",
        "agent.base-url=https://agent.example.test",
        "agent.token=test-agent-token",
        "agent.connect-timeout=3s",
        "agent.read-timeout=60s",
        "api.token=test-api-token",
        "api.session-signing-key=test-session-signing-key-at-least-32-characters",
        "api.session-ttl=30d",
        "api.session-cookie-secure=true",
        "report.cache-ttl=10m",
        "analysis.engine=agent",
        "analysis.rate-limit.enabled=true",
        "analysis.rate-limit.max-requests=5",
        "analysis.rate-limit.window=10m",
        "analysis.scheduling.enabled=true",
        "analysis.running-timeout=15m",
        "analysis.running-recovery.scheduler-fixed-delay-ms=30000",
        "analysis.running-recovery.batch-size=20",
        "analysis.outbox.fixed-delay-ms=5000",
        "analysis.outbox.batch-size=20",
        "analysis.outbox.max-attempts=10",
        "analysis.outbox.retry-delay=30s",
        "analysis.outbox.retry-max-delay=15m",
        "analysis.outbox.retry-jitter-ratio=0.2",
        "analysis.outbox.confirm-timeout=5s",
        "analysis.outbox.lease-duration=30s",
        "analysis.retention.scheduler-fixed-delay-ms=3600000",
        "analysis.retention.batch-size=200",
        "analysis.retention.outbox=30d",
        "analysis.retention.idempotency=30d",
        "analysis.retention.user-data=30d",
        "analysis.retry.delay=1m",
        "analysis.retry.max-delay=15m",
        "analysis.retry.jitter-ratio=0.2",
        "analysis.retry.scheduler-fixed-delay-ms=30000",
        "analysis.retry.batch-size=20",
        "resume.upload.max-file-size=5MB",
        "resume.upload.max-pdf-pages=50",
        "resume.upload.max-docx-entries=512",
        "resume.upload.max-docx-entry-size=10MB",
        "resume.upload.max-docx-uncompressed-size=20MB"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(PropertyConfiguration.class);

    @Test
    void bindsACompleteValidConfiguration() {
        contextRunner.withPropertyValues(VALID_PROPERTIES).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AiProperties.class);
            assertThat(context).hasSingleBean(AgentProperties.class);
            assertThat(context).hasSingleBean(ApiProperties.class);
            assertThat(context).hasSingleBean(ReportProperties.class);
            assertThat(context).hasSingleBean(AnalysisProperties.class);
            assertThat(context).hasSingleBean(ResumeProperties.class);
        });
    }

    @ParameterizedTest(name = "missing {0}")
    @MethodSource("requiredPropertyNames")
    void failsStartupWhenAnyRequiredCustomPropertyIsMissing(String missingProperty) {
        contextRunner.withPropertyValues(without(missingProperty)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasMessageContaining("Could not bind properties");
        });
    }

    @ParameterizedTest(name = "invalid override {0}")
    @MethodSource("invalidPropertyOverrides")
    void failsStartupWhenAPropertyViolatesItsContract(String invalidOverride) {
        contextRunner.withPropertyValues(replacing(invalidOverride)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasMessageContaining("Could not bind properties");
        });
    }

    private static Stream<String> requiredPropertyNames() {
        return Arrays.stream(VALID_PROPERTIES).map(ApplicationPropertiesValidationTest::key);
    }

    private static Stream<String> invalidPropertyOverrides() {
        return Stream.of(
            "ai.endpoint=ftp://example.test/model",
            "ai.api-key= ",
            "agent.base-url=/relative",
            "agent.token= ",
            "agent.connect-timeout=0s",
            "agent.read-timeout=2s",
            "api.token= ",
            "api.session-signing-key=too-short",
            "api.session-ttl=0s",
            "report.cache-ttl=-1s",
            "analysis.engine=automatic",
            "analysis.rate-limit.max-requests=0",
            "analysis.rate-limit.window=0s",
            "analysis.running-timeout=-1s",
            "analysis.running-recovery.batch-size=0",
            "analysis.outbox.max-attempts=0",
            "analysis.outbox.retry-delay=20m",
            "analysis.outbox.retry-jitter-ratio=1.1",
            "analysis.outbox.confirm-timeout=0s",
            "analysis.retention.batch-size=0",
            "analysis.retention.user-data=0s",
            "analysis.retry.delay=0s",
            "analysis.retry.max-delay=30s",
            "analysis.retry.jitter-ratio=1.1",
            "resume.upload.max-file-size=0B",
            "resume.upload.max-pdf-pages=0",
            "resume.upload.max-docx-entries=0",
            "resume.upload.max-docx-entry-size=21MB"
        );
    }

    private static String[] without(String missingProperty) {
        return Arrays.stream(VALID_PROPERTIES)
            .filter(property -> !key(property).equals(missingProperty))
            .toArray(String[]::new);
    }

    private static String[] replacing(String override) {
        Map<String, String> properties = new LinkedHashMap<>();
        Arrays.stream(VALID_PROPERTIES).forEach(property -> properties.put(key(property), property));
        properties.put(key(override), override);
        return properties.values().toArray(String[]::new);
    }

    private static String key(String property) {
        return property.substring(0, property.indexOf('='));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
        AiProperties.class,
        AgentProperties.class,
        ApiProperties.class,
        ReportProperties.class,
        AnalysisProperties.class,
        ResumeProperties.class
    })
    static class PropertyConfiguration {
    }
}

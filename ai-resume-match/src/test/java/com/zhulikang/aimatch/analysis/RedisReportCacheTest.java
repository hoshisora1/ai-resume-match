package com.zhulikang.aimatch.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class RedisReportCacheTest {
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RedisReportCache cache = new RedisReportCache(
        redisTemplate,
        objectMapper,
        Duration.ofMinutes(10),
        new AnalysisMetrics(meterRegistry)
    );

    @Test
    void returnsCachedReportFromJson() throws Exception {
        MatchReportView report = new MatchReportView(
            99L,
            88,
            "cached",
            "match-report-v2",
            "{\"schemaVersion\":\"match-report-v2\"}",
            "{\"schemaVersion\":\"analysis-run-v1\"}",
            LocalDateTime.now()
        );
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("match-report:99")).thenReturn(objectMapper.writeValueAsString(report));

        Optional<MatchReportView> result = cache.get(99L);

        assertThat(result).contains(report);
        assertThat(result.orElseThrow().structuredReportJson()).contains("match-report-v2");
        assertThat(meterRegistry.counter("report.cache.requests", "result", "hit").count()).isEqualTo(1.0);
    }

    @Test
    void readsLegacyCachedReportWithoutStructuredFields() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("match-report:99")).thenReturn("""
            {
              "taskId": 99,
              "matchScore": 88,
              "reportContent": "legacy cached report",
              "createdAt": "2026-08-13T09:00:00"
            }
            """);

        MatchReportView report = cache.get(99L).orElseThrow();

        assertThat(report.reportSchemaVersion()).isEqualTo("markdown-v1");
        assertThat(report.structuredReportJson()).isNull();
        assertThat(report.provenanceJson()).isNull();
    }

    @Test
    void returnsEmptyAndRecordsMissWhenCacheIsEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("match-report:99")).thenReturn(null);

        assertThat(cache.get(99L)).isEmpty();
        assertThat(meterRegistry.counter("report.cache.requests", "result", "miss").count()).isEqualTo(1.0);
    }

    @Test
    void returnsEmptyWhenCachedJsonIsInvalid() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("match-report:99")).thenReturn("{bad-json");

        assertThat(cache.get(99L)).isEmpty();
        assertThat(meterRegistry.counter("report.cache.requests", "result", "error").count()).isEqualTo(1.0);
    }

    @Test
    void returnsEmptyWhenRedisReadFailsWithoutLoggingConnectionDetails(CapturedOutput output) {
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException(
            "redis://cache-user:secret-password@internal-host:6379"
        ));

        assertThat(cache.get(99L)).isEmpty();
        assertThat(meterRegistry.counter("report.cache.requests", "result", "error").count()).isEqualTo(1.0);
        assertThat(output)
            .contains("event=report_cache_read_failed taskId=99")
            .contains("exceptionType=org.springframework.data.redis.RedisConnectionFailureException")
            .doesNotContain("secret-password")
            .doesNotContain("internal-host");
    }

    @Test
    void writesReportWithConfiguredTtl() {
        MatchReportView report = new MatchReportView(99L, 88, "cached", LocalDateTime.now());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        cache.put(report);

        verify(valueOperations).set(eq("match-report:99"), any(String.class), eq(Duration.ofMinutes(10)));
        assertThat(meterRegistry.counter("report.cache.writes", "outcome", "success").count()).isEqualTo(1.0);
    }

    @Test
    void ignoresRedisWriteFailureWithoutLoggingConnectionDetails(CapturedOutput output) {
        MatchReportView report = new MatchReportView(99L, 88, "cached", LocalDateTime.now());
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException(
            "redis://cache-user:secret-password@internal-host:6379"
        ));

        cache.put(report);
        assertThat(meterRegistry.counter("report.cache.writes", "outcome", "error").count()).isEqualTo(1.0);
        assertThat(output)
            .contains("event=report_cache_write_failed taskId=99")
            .contains("exceptionType=org.springframework.data.redis.RedisConnectionFailureException")
            .doesNotContain("secret-password")
            .doesNotContain("internal-host");
    }

    @Test
    void evictsReportAndRecordsSuccess() {
        when(redisTemplate.delete("match-report:99")).thenReturn(true);

        cache.evict(99L);

        verify(redisTemplate).delete("match-report:99");
        assertThat(meterRegistry.counter(
            "report.cache.evictions",
            "outcome",
            "success"
        ).count()).isEqualTo(1.0);
    }

    @Test
    void ignoresRedisEvictionFailureWithoutLoggingConnectionDetails(CapturedOutput output) {
        when(redisTemplate.delete("match-report:99")).thenThrow(new RedisConnectionFailureException(
            "redis://cache-user:secret-password@internal-host:6379"
        ));

        cache.evict(99L);

        assertThat(meterRegistry.counter(
            "report.cache.evictions",
            "outcome",
            "error"
        ).count()).isEqualTo(1.0);
        assertThat(output)
            .contains("event=report_cache_evict_failed taskId=99")
            .doesNotContain("secret-password")
            .doesNotContain("internal-host");
    }
}

package com.zhulikang.aimatch.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
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
        MatchReportView report = new MatchReportView(99L, 88, "cached", LocalDateTime.now());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("match-report:99")).thenReturn(objectMapper.writeValueAsString(report));

        Optional<MatchReportView> result = cache.get(99L);

        assertThat(result).contains(report);
        assertThat(meterRegistry.counter("report.cache.requests", "result", "hit").count()).isEqualTo(1.0);
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
    void returnsEmptyWhenRedisReadFails() {
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.get(99L)).isEmpty();
        assertThat(meterRegistry.counter("report.cache.requests", "result", "error").count()).isEqualTo(1.0);
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
    void ignoresRedisWriteFailure() {
        MatchReportView report = new MatchReportView(99L, 88, "cached", LocalDateTime.now());
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));

        cache.put(report);
        assertThat(meterRegistry.counter("report.cache.writes", "outcome", "error").count()).isEqualTo(1.0);
    }
}

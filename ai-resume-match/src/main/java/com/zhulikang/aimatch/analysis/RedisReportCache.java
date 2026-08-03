package com.zhulikang.aimatch.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class RedisReportCache implements ReportCache {
    private static final Logger log = LoggerFactory.getLogger(RedisReportCache.class);
    private static final String KEY_PREFIX = "match-report:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final AnalysisMetrics metrics;

    public RedisReportCache(
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        @Value("${report.cache-ttl:10m}") Duration ttl,
        AnalysisMetrics metrics
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
        this.metrics = metrics;
    }

    @Override
    public Optional<MatchReportView> get(Long taskId) {
        try {
            String value = redisTemplate.opsForValue().get(key(taskId));
            if (value == null || value.isBlank()) {
                metrics.cacheRequest("miss");
                return Optional.empty();
            }
            MatchReportView report = objectMapper.readValue(value, MatchReportView.class);
            metrics.cacheRequest("hit");
            return Optional.of(report);
        } catch (RuntimeException | JsonProcessingException ex) {
            metrics.cacheRequest("error");
            log.warn(
                "event=report_cache_read_failed taskId={} exceptionType={}",
                taskId,
                ex.getClass().getName()
            );
            return Optional.empty();
        }
    }

    @Override
    public void put(MatchReportView report) {
        try {
            redisTemplate.opsForValue().set(key(report.taskId()), objectMapper.writeValueAsString(report), ttl);
            metrics.cacheWrite("success");
        } catch (RuntimeException | JsonProcessingException ex) {
            metrics.cacheWrite("error");
            log.warn(
                "event=report_cache_write_failed taskId={} exceptionType={}",
                report.taskId(),
                ex.getClass().getName()
            );
        }
    }

    private String key(Long taskId) {
        return KEY_PREFIX + taskId;
    }
}

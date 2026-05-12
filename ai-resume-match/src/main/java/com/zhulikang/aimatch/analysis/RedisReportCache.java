package com.zhulikang.aimatch.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    public RedisReportCache(
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        @Value("${report.cache-ttl:10m}") Duration ttl
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    @Override
    public Optional<MatchReportView> get(Long taskId) {
        try {
            String value = redisTemplate.opsForValue().get(key(taskId));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, MatchReportView.class));
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Failed to read match report cache for task {}: {}", taskId, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(MatchReportView report) {
        try {
            redisTemplate.opsForValue().set(key(report.taskId()), objectMapper.writeValueAsString(report), ttl);
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Failed to write match report cache for task {}: {}", report.taskId(), ex.getMessage());
        }
    }

    private String key(Long taskId) {
        return KEY_PREFIX + taskId;
    }
}

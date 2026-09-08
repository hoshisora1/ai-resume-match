package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.config.AnalysisProperties;
import com.zhulikang.aimatch.security.OwnerId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@ConditionalOnProperty(name = "analysis.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RedisAnalysisRequestRateLimiter implements AnalysisRequestRateLimiter {
    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>("""
        local current = redis.call('INCR', KEYS[1])
        if current == 1 then
          redis.call('PEXPIRE', KEYS[1], ARGV[1])
        end
        if current > tonumber(ARGV[2]) then
          local ttl = redis.call('PTTL', KEYS[1])
          if ttl < 1 then
            return tonumber(ARGV[1])
          end
          return ttl
        end
        return -1
        """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final long windowMillis;
    private final int maxRequests;

    @Autowired
    public RedisAnalysisRequestRateLimiter(
        StringRedisTemplate redisTemplate,
        AnalysisProperties properties
    ) {
        this(
            redisTemplate,
            properties.rateLimit().window(),
            properties.rateLimit().maxRequests()
        );
    }

    RedisAnalysisRequestRateLimiter(
        StringRedisTemplate redisTemplate,
        Duration window,
        int maxRequests
    ) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("analysis.rate-limit.window must be positive");
        }
        if (maxRequests < 1) {
            throw new IllegalArgumentException("analysis.rate-limit.max-requests must be positive");
        }
        this.redisTemplate = redisTemplate;
        this.windowMillis = window.toMillis();
        this.maxRequests = maxRequests;
    }

    @Override
    public void consume(String ownerId) {
        String key = "analysis:rate-limit:" + OwnerId.requireValid(ownerId);
        try {
            Long retryAfterMillis = redisTemplate.execute(
                CONSUME_SCRIPT,
                List.of(key),
                Long.toString(windowMillis),
                Integer.toString(maxRequests)
            );
            if (retryAfterMillis == null) {
                throw new IllegalStateException("Redis rate-limit script returned no result");
            }
            if (retryAfterMillis >= 0) {
                long retryAfterSeconds = Math.max(1, (retryAfterMillis + 999) / 1000);
                throw new RateLimitExceededException(retryAfterSeconds);
            }
        } catch (RateLimitExceededException exceeded) {
            throw exceeded;
        } catch (RuntimeException unavailable) {
            throw new RateLimitUnavailableException(unavailable);
        }
    }
}

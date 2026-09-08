package com.zhulikang.aimatch.application.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisAnalysisRequestRateLimiterTest {
    private static final String OWNER_ID = "a".repeat(64);

    @Test
    void allowsRequestWhileAtomicRedisCounterIsWithinQuota() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), any(), any())).thenReturn(-1L);
        RedisAnalysisRequestRateLimiter limiter = new RedisAnalysisRequestRateLimiter(
            redis,
            Duration.ofMinutes(10),
            5
        );

        limiter.consume(OWNER_ID);
    }

    @Test
    void exposesRetryAfterWhenQuotaIsExceeded() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), any(), any())).thenReturn(42_001L);
        RedisAnalysisRequestRateLimiter limiter = new RedisAnalysisRequestRateLimiter(
            redis,
            Duration.ofMinutes(10),
            5
        );

        assertThatThrownBy(() -> limiter.consume(OWNER_ID))
            .isInstanceOf(RateLimitExceededException.class)
            .extracting("retryAfterSeconds")
            .isEqualTo(43L);
    }

    @Test
    void failsClosedWhenRedisCannotEnforcePaidRequestQuota() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), any(), any()))
            .thenThrow(new RedisConnectionFailureException("down"));
        RedisAnalysisRequestRateLimiter limiter = new RedisAnalysisRequestRateLimiter(
            redis,
            Duration.ofMinutes(10),
            5
        );

        assertThatThrownBy(() -> limiter.consume(OWNER_ID))
            .isInstanceOf(RateLimitUnavailableException.class)
            .hasMessage("Analysis request quota is temporarily unavailable");
    }
}

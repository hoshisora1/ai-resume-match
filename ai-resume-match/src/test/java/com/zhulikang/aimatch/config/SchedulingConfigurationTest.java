package com.zhulikang.aimatch.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SchedulingConfigurationTest {
    private static final String SCHEDULED_PROCESSOR_BEAN =
        "org.springframework.context.annotation.internalScheduledAnnotationProcessor";

    @Autowired
    private ApplicationContext context;

    @MockBean
    private RabbitTemplate rabbitTemplate;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @Test
    void testProfileDoesNotStartBackgroundSchedulers() {
        assertThat(context.containsBean(SCHEDULED_PROCESSOR_BEAN)).isFalse();
        assertThat(context.getBeansOfType(SchedulingConfiguration.class)).isEmpty();
    }
}

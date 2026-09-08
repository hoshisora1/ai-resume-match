package com.zhulikang.aimatch.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "management.endpoints.web.exposure.include=health,prometheus",
    "management.endpoint.health.probes.enabled=true",
    "management.endpoint.health.group.readiness.include=readinessState,db",
    "management.metrics.tags.application=ai-resume-match"
})
@AutoConfigureMockMvc
@AutoConfigureObservability
class PrometheusEndpointTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    HealthEndpointGroups healthEndpointGroups;

    @MockBean
    StringRedisTemplate redisTemplate;

    @MockBean
    RabbitTemplate rabbitTemplate;

    @Test
    void exposesPrometheusOutboxAgeGaugeWithApplicationTag() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("analysis_outbox_oldest_age_seconds")))
            .andExpect(content().string(containsString("application=\"ai-resume-match\"")));
    }

    @Test
    void readinessIncludesApplicationStateAndDatabaseButNotRedis() {
        HealthEndpointGroup readiness = healthEndpointGroups.get("readiness");

        assertThat(readiness).isNotNull();
        assertThat(readiness.isMember("readinessState")).isTrue();
        assertThat(readiness.isMember("db")).isTrue();
        assertThat(readiness.isMember("redis")).isFalse();
    }
}

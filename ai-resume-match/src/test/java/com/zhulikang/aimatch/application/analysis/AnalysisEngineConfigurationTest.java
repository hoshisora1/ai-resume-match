package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.ai.AiClient;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "analysis.engine=agent",
    "agent.base-url=http://agent.test:8000",
    "agent.token=test-agent-token"
})
class AnalysisEngineConfigurationTest {
    @Autowired
    private AnalysisEngine analysisEngine;

    @Autowired
    private ApplicationContext context;

    @MockBean
    private RabbitTemplate rabbitTemplate;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @Test
    void agentModeLoadsOnlyAgentAnalysisImplementation() {
        assertThat(analysisEngine).isInstanceOf(AgentServiceAnalysisEngine.class);
        assertThat(context.getBeansOfType(AnalysisEngine.class))
            .containsOnlyKeys("agentServiceAnalysisEngine");
        assertThat(context.getBeansOfType(AiClient.class)).isEmpty();
    }
}

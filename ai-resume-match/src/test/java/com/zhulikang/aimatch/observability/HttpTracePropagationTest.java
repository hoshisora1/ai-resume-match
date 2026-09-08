package com.zhulikang.aimatch.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest(properties = {
    "management.tracing.enabled=true",
    "management.tracing.sampling.probability=1.0",
    "management.otlp.tracing.export.enabled=false"
})
@AutoConfigureObservability
class HttpTracePropagationTest {
    @Autowired
    RestTemplateBuilder restTemplateBuilder;

    @Autowired
    Tracer tracer;

    @org.springframework.boot.test.mock.mockito.MockBean
    StringRedisTemplate redisTemplate;

    @org.springframework.boot.test.mock.mockito.MockBean
    RabbitTemplate rabbitTemplate;

    @Test
    void springManagedRestTemplatePropagatesTheCurrentW3cTrace() {
        RestTemplate restTemplate = restTemplateBuilder.build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        Span span = tracer.nextSpan().name("trace-propagation-test").start();
        String traceId = span.context().traceId();
        server.expect(requestTo("http://agent.test/health"))
            .andExpect(header(TraceContextHeaders.TRACEPARENT_HEADER, containsString(traceId)))
            .andRespond(withSuccess("ok", null));

        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            assertThat(TraceContextHeaders.captureCurrentTraceParent()).contains(traceId);
            assertThat(restTemplate.getForObject("http://agent.test/health", String.class)).isEqualTo("ok");
        } finally {
            span.end();
        }

        assertThat(traceId).matches("[0-9a-f]{32}");
        server.verify();
    }
}

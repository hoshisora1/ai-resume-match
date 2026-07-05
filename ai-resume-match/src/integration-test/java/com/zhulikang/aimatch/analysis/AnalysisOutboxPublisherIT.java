package com.zhulikang.aimatch.analysis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class AnalysisOutboxPublisherIT {
    @Container
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management");

    private CachingConnectionFactory connectionFactory;

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void publishesOutboxEventToAnalysisQueue() {
        connectionFactory = new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
        connectionFactory.setUsername(rabbit.getAdminUsername());
        connectionFactory.setPassword(rabbit.getAdminPassword());
        declareTopology(connectionFactory);

        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        AnalysisOutboxRepository repository = mock(AnalysisOutboxRepository.class);
        AnalysisOutboxEvent event = AnalysisOutboxEvent.analysisRequested(99L);
        when(repository.findDueForPublish(anyList(), any(), any())).thenReturn(List.of(event));
        AnalysisOutboxPublisher publisher = new AnalysisOutboxPublisher(
            repository,
            rabbitTemplate,
            20,
            Duration.ofSeconds(30),
            Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC)
        );

        publisher.publishPending();

        Object message = rabbitTemplate.receiveAndConvert(RabbitConfig.ANALYSIS_QUEUE, 5000);
        assertThat(message).isEqualTo(99L);
        assertThat(event.getStatus()).isEqualTo(AnalysisOutboxStatus.PUBLISHED);
        verify(repository).save(event);
    }

    private void declareTopology(CachingConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        DirectExchange exchange = new DirectExchange(RabbitConfig.ANALYSIS_EXCHANGE, true, false);
        DirectExchange deadLetterExchange = new DirectExchange(RabbitConfig.ANALYSIS_DLX, true, false);
        Queue queue = new Queue(RabbitConfig.ANALYSIS_QUEUE, true);
        Queue deadLetterQueue = new Queue(RabbitConfig.ANALYSIS_DLQ, true);
        admin.declareExchange(exchange);
        admin.declareExchange(deadLetterExchange);
        admin.declareQueue(queue);
        admin.declareQueue(deadLetterQueue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(RabbitConfig.ANALYSIS_ROUTING_KEY));
        admin.declareBinding(BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(RabbitConfig.ANALYSIS_DLQ));
    }
}

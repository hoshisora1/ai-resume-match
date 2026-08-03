package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.observability.AnalysisMetrics;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AnalysisOutboxPublisherIT.RabbitTestConfig.class)
class AnalysisOutboxPublisherIT {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("ai_resume_match")
        .withUsername("test")
        .withPassword("test");

    @Container
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    AnalysisOutboxRepository outboxRepository;
    @Autowired
    AnalysisTaskRepository taskRepository;
    @Autowired
    MatchReportRepository reportRepository;
    @Autowired
    ResumeRepository resumeRepository;
    @Autowired
    JobDescriptionRepository jobRepository;
    @Autowired
    RabbitTemplate rabbitTemplate;
    @Autowired
    CachingConnectionFactory connectionFactory;
    @Autowired
    PlatformTransactionManager transactionManager;

    private AnalysisOutboxPublisher publisher;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("analysis.outbox.batch-size", () -> "20");
        registry.add("analysis.outbox.retry-delay", () -> "30s");
        registry.add("analysis.outbox.confirm-timeout", () -> "5s");
    }

    @BeforeEach
    void resetRabbitTopology() {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.deleteQueue(RabbitConfig.ANALYSIS_QUEUE);
        admin.deleteQueue(RabbitConfig.ANALYSIS_DLQ);
        admin.deleteExchange(RabbitConfig.ANALYSIS_EXCHANGE);
        admin.deleteExchange(RabbitConfig.ANALYSIS_DLX);
        publisher = publisher(10);
    }

    @Test
    void publishesOutboxEventFromMySqlToAnalysisQueueAndMarksPublished() {
        declareAnalysisTopology();
        AnalysisOutboxEvent event = outboxRepository.saveAndFlush(AnalysisOutboxEvent.analysisRequested(99L));

        publishPending();

        Object message = rabbitTemplate.receiveAndConvert(RabbitConfig.ANALYSIS_QUEUE, 5000);
        assertThat(message).isEqualTo(99L);
        AnalysisOutboxEvent updated = outboxRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(AnalysisOutboxStatus.PUBLISHED);
        assertThat(updated.getPublishedAt()).isNotNull();
        assertThat(updated.getLastError()).isNull();
    }

    @Test
    void marksOutboxFailedWhenRabbitReturnsUnroutableMessage() {
        declareExchangeOnly();
        AnalysisOutboxEvent event = outboxRepository.saveAndFlush(AnalysisOutboxEvent.analysisRequested(99L));

        publishPending();

        AnalysisOutboxEvent updated = outboxRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(AnalysisOutboxStatus.FAILED);
        assertThat(updated.getAttemptCount()).isEqualTo(1);
        assertThat(updated.getNextAttemptAt()).isNotNull();
        assertThat(updated.getLastError()).contains("returned");
    }

    @Test
    void movesExhaustedPublishToDeadAndDoesNotClaimItAgain() {
        declareExchangeOnly();
        publisher = publisher(1);
        Resume resume = resumeRepository.saveAndFlush(new Resume("resume.pdf", "Java", "Java"));
        JobDescription job = jobRepository.saveAndFlush(new JobDescription("Java", "Java", "Java"));
        AnalysisTask task = taskRepository.saveAndFlush(new AnalysisTask(resume.getId(), job.getId()));
        AnalysisOutboxEvent event = outboxRepository.saveAndFlush(
            AnalysisOutboxEvent.analysisRequested(task.getId())
        );

        publishPending();
        publishPending();

        AnalysisOutboxEvent updated = outboxRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(AnalysisOutboxStatus.DEAD);
        assertThat(updated.getAttemptCount()).isEqualTo(1);
        assertThat(updated.getNextAttemptAt()).isNull();
        assertThat(updated.getLastError()).contains("returned");
        AnalysisTask failedTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(failedTask.getStatus()).isEqualTo(AnalysisTask.Status.FAILED_RETRYABLE);
        assertThat(failedTask.getFailureCode()).isEqualTo(AnalysisFailureCode.DELIVERY_FAILED);
        assertThat(failedTask.getNextRetryAt()).isNull();
    }

    private void declareAnalysisTopology() {
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

    private void declareExchangeOnly() {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.declareExchange(new DirectExchange(RabbitConfig.ANALYSIS_EXCHANGE, true, false));
    }

    private void publishPending() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> publisher.publishPending());
    }

    private AnalysisOutboxPublisher publisher(int maxAttempts) {
        return new AnalysisOutboxPublisher(
            outboxRepository,
            new AnalysisTaskService(
                taskRepository,
                reportRepository,
                Duration.ofMinutes(15)
            ),
            rabbitTemplate,
            20,
            maxAttempts,
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
            Clock.systemDefaultZone(),
            new AnalysisMetrics(new SimpleMeterRegistry())
        );
    }

    @TestConfiguration
    static class RabbitTestConfig {
        @Bean(destroyMethod = "destroy")
        CachingConnectionFactory testRabbitConnectionFactory() {
            CachingConnectionFactory factory = new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
            factory.setUsername(rabbit.getAdminUsername());
            factory.setPassword(rabbit.getAdminPassword());
            factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            factory.setPublisherReturns(true);
            return factory;
        }

        @Bean
        RabbitTemplate testRabbitTemplate(CachingConnectionFactory connectionFactory) {
            RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
            rabbitTemplate.setMandatory(true);
            rabbitTemplate.setReturnsCallback(returned -> {
            });
            return rabbitTemplate;
        }
    }
}

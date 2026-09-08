package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.observability.AnalysisMetrics;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
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
    AnalysisSubmissionIdempotencyRepository idempotencyRepository;
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
        outboxRepository.deleteAll();
        reportRepository.deleteAll();
        idempotencyRepository.deleteAll();
        taskRepository.deleteAll();
        jobRepository.deleteAll();
        resumeRepository.deleteAll();

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
        Resume resume = resumeRepository.saveAndFlush(new Resume("resume.pdf", "Java"));
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

    @Test
    void brokerOutagePersistsFailureAndRecoveredRetryPublishes() throws Exception {
        declareAnalysisTopology();
        AnalysisOutboxEvent event = outboxRepository.saveAndFlush(
            AnalysisOutboxEvent.analysisRequested(99L)
        );

        stopRabbitApplication();
        try {
            publishPending();

            AnalysisOutboxEvent failed = outboxRepository.findById(event.getId()).orElseThrow();
            assertThat(failed.getStatus()).isEqualTo(AnalysisOutboxStatus.FAILED);
            assertThat(failed.getAttemptCount()).isEqualTo(1);
            assertThat(failed.getNextAttemptAt()).isNotNull();
            assertThat(failed.getLastError()).isNotBlank();
            assertThat(failed.getPublishedAt()).isNull();
        } finally {
            startRabbitApplication();
        }

        publisher = publisher(
            10,
            Clock.offset(Clock.systemDefaultZone(), Duration.ofSeconds(31))
        );
        publishPending();

        assertThat(rabbitTemplate.receiveAndConvert(RabbitConfig.ANALYSIS_QUEUE, 5000))
            .isEqualTo(99L);
        assertThat(rabbitTemplate.receiveAndConvert(RabbitConfig.ANALYSIS_QUEUE, 250))
            .isNull();
        AnalysisOutboxEvent published = outboxRepository.findById(event.getId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(AnalysisOutboxStatus.PUBLISHED);
        assertThat(published.getAttemptCount()).isEqualTo(1);
        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(published.getLastError()).isNull();
    }

    @Test
    void retentionPurgesOnlyExpiredTerminalAndIdempotencyRowsOnMySql84() {
        Clock retentionClock = Clock.fixed(
            Instant.parse("2026-08-13T00:00:00Z"),
            ZoneOffset.UTC
        );
        LocalDateTime now = LocalDateTime.now(retentionClock);
        Resume resume = resumeRepository.saveAndFlush(new Resume("resume.pdf", "Java"));
        JobDescription job = jobRepository.saveAndFlush(new JobDescription("Java", "Java", "Java"));
        AnalysisTask task = new AnalysisTask(resume.getId(), job.getId());
        ReflectionTestUtils.setField(task, "status", AnalysisTask.Status.SUCCESS);
        ReflectionTestUtils.setField(task, "completedAt", now.minusDays(31));
        task = taskRepository.saveAndFlush(task);

        AnalysisSubmissionIdempotencyRecord expiredRecord = new AnalysisSubmissionIdempotencyRecord(
            "a".repeat(64),
            "b".repeat(64)
        );
        expiredRecord.complete(task.getId());
        ReflectionTestUtils.setField(expiredRecord, "createdAt", now.minusDays(31));
        expiredRecord = idempotencyRepository.saveAndFlush(expiredRecord);
        AnalysisTask activeTask = taskRepository.saveAndFlush(
            new AnalysisTask(resume.getId(), job.getId())
        );
        AnalysisSubmissionIdempotencyRecord activeRecord = new AnalysisSubmissionIdempotencyRecord(
            "c".repeat(64),
            "d".repeat(64)
        );
        activeRecord.complete(activeTask.getId());
        ReflectionTestUtils.setField(activeRecord, "createdAt", now.minusDays(31));
        activeRecord = idempotencyRepository.saveAndFlush(activeRecord);

        AnalysisOutboxEvent expiredPublished = AnalysisOutboxEvent.analysisRequested(task.getId());
        expiredPublished.markPublished(now.minusDays(31));
        expiredPublished = outboxRepository.saveAndFlush(expiredPublished);
        AnalysisOutboxEvent recentPublished = AnalysisOutboxEvent.analysisRequested(task.getId());
        recentPublished.markPublished(now.minusDays(29));
        recentPublished = outboxRepository.saveAndFlush(recentPublished);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AnalysisRetentionScheduler retention = new AnalysisRetentionScheduler(
            outboxRepository,
            idempotencyRepository,
            new AnalysisMetrics(meterRegistry),
            Duration.ofDays(30),
            Duration.ofDays(30),
            20,
            retentionClock
        );
        new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> retention.purgeExpiredRecords());

        assertThat(outboxRepository.findById(expiredPublished.getId())).isEmpty();
        assertThat(outboxRepository.findById(recentPublished.getId())).isPresent();
        assertThat(idempotencyRepository.findById(expiredRecord.getId())).isEmpty();
        assertThat(idempotencyRepository.findById(activeRecord.getId())).isPresent();
        assertThat(taskRepository.findById(task.getId())).isPresent();
        assertThat(meterRegistry.counter(
            "analysis.retention.deleted",
            "resource",
            "outbox"
        ).count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter(
            "analysis.retention.deleted",
            "resource",
            "idempotency"
        ).count()).isEqualTo(1.0);
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
        publisher.publishPending();
    }

    private AnalysisOutboxPublisher publisher(int maxAttempts) {
        return publisher(maxAttempts, Clock.systemDefaultZone());
    }

    private AnalysisOutboxPublisher publisher(int maxAttempts, Clock clock) {
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
            Duration.ofSeconds(30),
            clock,
            new AnalysisMetrics(new SimpleMeterRegistry()),
            outboxTransactions()
        );
    }

    private TransactionTemplate outboxTransactions() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactions;
    }

    private void stopRabbitApplication() throws Exception {
        var result = rabbit.execInContainer("rabbitmqctl", "stop_app");
        assertThat(result.getExitCode()).isZero();
    }

    private void startRabbitApplication() throws Exception {
        var result = rabbit.execInContainer("rabbitmqctl", "start_app");
        assertThat(result.getExitCode()).isZero();
        Awaitility.await()
            .atMost(Duration.ofSeconds(15))
            .ignoreExceptions()
            .until(() -> Boolean.TRUE.equals(
                rabbitTemplate.execute(channel -> channel.isOpen())
            ));
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

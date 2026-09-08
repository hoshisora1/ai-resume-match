package com.zhulikang.aimatch.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zhulikang.aimatch.application.analysis.AgentServiceAnalysisEngine;
import com.zhulikang.aimatch.application.analysis.AnalysisEngine;
import com.zhulikang.aimatch.application.analysis.AnalysisResult;
import com.zhulikang.aimatch.application.analysis.AnalysisTaskPublisher;
import com.zhulikang.aimatch.application.analysis.ModelInputPrivacySanitizer;
import com.zhulikang.aimatch.application.analysis.RunAnalysisUseCase;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    AnalysisPublisherWorkerConcurrencyIT.RabbitTestConfig.class,
    AnalysisTaskService.class
})
class AnalysisPublisherWorkerConcurrencyIT {
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
    AnalysisTaskService taskService;
    @Autowired
    RabbitTemplate rabbitTemplate;
    @Autowired
    CachingConnectionFactory connectionFactory;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("analysis.running-timeout", () -> "15m");
        registry.add("analysis.retry.delay", () -> "1m");
    }

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        reportRepository.deleteAll();
        taskRepository.deleteAll();
        jobRepository.deleteAll();
        resumeRepository.deleteAll();

        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.deleteQueue(RabbitConfig.ANALYSIS_QUEUE);
        admin.deleteQueue(RabbitConfig.ANALYSIS_DLQ);
        admin.deleteExchange(RabbitConfig.ANALYSIS_EXCHANGE);
        admin.deleteExchange(RabbitConfig.ANALYSIS_DLX);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void twoPublishersCompeteForOneOutboxEventAndEmitOneMessage() throws Exception {
        declareAnalysisTopology();
        AnalysisOutboxEvent event = outboxRepository.saveAndFlush(
            AnalysisOutboxEvent.analysisRequested(99L)
        );
        RabbitTemplate blockingTemplate = spy(rabbitTemplate);
        CountDownLatch sendEntered = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        doAnswer(invocation -> {
            sendEntered.countDown();
            if (!releaseSend.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release Rabbit publish");
            }
            return invocation.callRealMethod();
        }).when(blockingTemplate).convertAndSend(
            anyString(),
            anyString(),
            any(Object.class),
            any(MessagePostProcessor.class),
            any(CorrelationData.class)
        );

        AnalysisOutboxPublisher firstPublisher = publisher(blockingTemplate);
        AnalysisOutboxPublisher secondPublisher = publisher(blockingTemplate);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> first = publishConcurrently(firstPublisher, ready, start);
        Future<?> second = publishConcurrently(secondPublisher, ready, start);

        try {
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(sendEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> first.isDone() || second.isDone());
            assertThat(first.isDone() ^ second.isDone())
                .as("the losing publisher must finish while the winner waits for Rabbit confirm")
                .isTrue();
        } finally {
            releaseSend.countDown();
        }
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);

        assertThat(rabbitTemplate.receiveAndConvert(RabbitConfig.ANALYSIS_QUEUE, 5000))
            .isEqualTo(99L);
        assertThat(rabbitTemplate.receiveAndConvert(RabbitConfig.ANALYSIS_QUEUE, 250))
            .isNull();
        AnalysisOutboxEvent updated = outboxRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(AnalysisOutboxStatus.PUBLISHED);
        assertThat(updated.getPublishedAt()).isNotNull();
        verify(blockingTemplate, times(1)).convertAndSend(
            anyString(),
            anyString(),
            any(Object.class),
            any(MessagePostProcessor.class),
            any(CorrelationData.class)
        );
    }

    @Test
    void duplicateDeliveriesLetOneWorkerExecuteAndPersistOneReport() throws Exception {
        Resume resume = resumeRepository.saveAndFlush(
            new Resume("resume.pdf", "Java Redis")
        );
        JobDescription job = jobRepository.saveAndFlush(
            new JobDescription("Backend Engineer", "Java Redis", "Java,Redis")
        );
        AnalysisTask task = taskRepository.saveAndFlush(
            new AnalysisTask(resume.getId(), job.getId())
        );

        AnalysisEngine engine = mock(AnalysisEngine.class);
        CountDownLatch engineEntered = new CountDownLatch(1);
        CountDownLatch releaseEngine = new CountDownLatch(1);
        when(engine.analyze(any())).thenAnswer(invocation -> {
            engineEntered.countDown();
            if (!releaseEngine.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release analysis engine");
            }
            return new AnalysisResult(86, "# Match report");
        });
        RunAnalysisUseCase runner = new RunAnalysisUseCase(
            taskService,
            taskRepository,
            resumeRepository,
            jobRepository,
            engine,
            new AnalysisMetrics(new SimpleMeterRegistry()),
            new ModelInputPrivacySanitizer()
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> first = runConcurrently(runner, task.getId(), false, ready, start);
        Future<?> redelivery = runConcurrently(runner, task.getId(), true, ready, start);

        try {
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(engineEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> first.isDone() || redelivery.isDone());
            assertThat(first.isDone() ^ redelivery.isDone()).isTrue();
        } finally {
            releaseEngine.countDown();
        }
        first.get(10, TimeUnit.SECONDS);
        redelivery.get(10, TimeUnit.SECONDS);

        verify(engine, times(1)).analyze(any());
        AnalysisTask completed = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(AnalysisTask.Status.SUCCESS);
        assertThat(completed.getAttemptCount()).isEqualTo(1);
        assertThat(reportRepository.findByTaskId(task.getId()))
            .get()
            .extracting(MatchReport::getMatchScore)
            .isEqualTo(86);
        assertThat(reportRepository.count()).isEqualTo(1);
    }

    @Test
    void agentReadTimeoutMakesTaskRetryableWithoutPersistingAReport() throws Exception {
        Resume resume = resumeRepository.saveAndFlush(
            new Resume("resume.pdf", "Java Redis")
        );
        JobDescription job = jobRepository.saveAndFlush(
            new JobDescription("Backend Engineer", "Java Redis", "Java,Redis")
        );
        AnalysisTask task = taskRepository.saveAndFlush(
            new AnalysisTask(resume.getId(), job.getId())
        );
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        HttpServer slowAgent = slowAgent(requestReceived, releaseResponse);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentServiceAnalysisEngine engine = new AgentServiceAnalysisEngine(
            new RestTemplateBuilder(),
            "http://127.0.0.1:" + slowAgent.getAddress().getPort(),
            "agent-secret",
            Duration.ofSeconds(1),
            Duration.ofMillis(250),
            new AnalysisMetrics(registry),
            new ObjectMapper()
        );
        RunAnalysisUseCase runner = new RunAnalysisUseCase(
            taskService,
            taskRepository,
            resumeRepository,
            jobRepository,
            engine,
            new AnalysisMetrics(registry),
            new ModelInputPrivacySanitizer()
        );

        long startedAt = System.nanoTime();
        try {
            runner.run(task.getId(), false);
        } finally {
            releaseResponse.countDown();
            slowAgent.stop(0);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(requestReceived.getCount()).isZero();
        assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
        AnalysisTask failed = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(AnalysisTask.Status.FAILED_RETRYABLE);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getFailureCode()).isEqualTo(AnalysisFailureCode.AI_UNAVAILABLE);
        assertThat(failed.getFailureMessage()).isEqualTo(
            "Analysis service is temporarily unavailable"
        );
        assertThat(failed.getNextRetryAt()).isNotNull();
        assertThat(reportRepository.findByTaskId(task.getId())).isEmpty();
        assertThat(registry.counter("agent.calls", "outcome", "failure").count())
            .isEqualTo(1.0);
    }

    @Test
    void workerTerminationAfterClaimIsRecoveredAndCompletesOnSecondAttempt() throws Exception {
        declareAnalysisTopology();
        Resume resume = resumeRepository.saveAndFlush(
            new Resume("resume.pdf", "Java Redis")
        );
        JobDescription job = jobRepository.saveAndFlush(
            new JobDescription("Backend Engineer", "Java Redis", "Java,Redis")
        );
        AnalysisTask task = taskRepository.saveAndFlush(
            new AnalysisTask(resume.getId(), job.getId())
        );

        assertThat(taskService.tryStart(task.getId(), false)).hasValue(1);
        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
            .isEqualTo(AnalysisTask.Status.RUNNING);

        Clock recoveryClock = Clock.fixed(
            Instant.parse("2026-08-13T00:00:00Z"),
            ZoneOffset.UTC
        );
        LocalDateTime recoveryNow = LocalDateTime.now(recoveryClock);
        assertThat(jdbcTemplate.update(
            "update analysis_task set updated_at = ? where id = ?",
            recoveryNow.minusMinutes(16),
            task.getId()
        )).isEqualTo(1);
        RunningTaskRecoveryScheduler recovery = new RunningTaskRecoveryScheduler(
            taskRepository,
            new AnalysisTaskPublisher(outboxRepository),
            Duration.ofMinutes(15),
            20,
            recoveryClock
        );

        new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> recovery.recoverStaleRunningTasks());

        AnalysisTask requeued = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(AnalysisTask.Status.PENDING);
        assertThat(requeued.getAttemptCount()).isEqualTo(1);
        assertThat(requeued.getStartedAt()).isNull();
        assertThat(outboxRepository.findAll())
            .singleElement()
            .satisfies(event -> {
                assertThat(event.getAggregateId()).isEqualTo(task.getId());
                assertThat(event.getStatus()).isEqualTo(AnalysisOutboxStatus.PENDING);
            });

        AnalysisOutboxPublisher outboxPublisher = publisher(rabbitTemplate);
        outboxPublisher.publishPending();
        assertThat(rabbitTemplate.receiveAndConvert(RabbitConfig.ANALYSIS_QUEUE, 5000))
            .isEqualTo(task.getId());

        AnalysisEngine engine = mock(AnalysisEngine.class);
        when(engine.analyze(any())).thenReturn(new AnalysisResult(91, "# Recovered report"));
        RunAnalysisUseCase runner = new RunAnalysisUseCase(
            taskService,
            taskRepository,
            resumeRepository,
            jobRepository,
            engine,
            new AnalysisMetrics(new SimpleMeterRegistry()),
            new ModelInputPrivacySanitizer()
        );
        runner.run(task.getId(), true);

        verify(engine, times(1)).analyze(any());
        AnalysisTask completed = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(AnalysisTask.Status.SUCCESS);
        assertThat(completed.getAttemptCount()).isEqualTo(2);
        assertThat(reportRepository.findByTaskId(task.getId()))
            .get()
            .extracting(MatchReport::getMatchScore)
            .isEqualTo(91);
        assertThat(reportRepository.count()).isEqualTo(1);
    }

    @Test
    void publisherTerminationAfterBrokerAckRepublishesButWorkerExecutesOnlyOnce() {
        declareAnalysisTopology();
        Resume resume = resumeRepository.saveAndFlush(
            new Resume("resume.pdf", "Java Redis")
        );
        JobDescription job = jobRepository.saveAndFlush(
            new JobDescription("Backend Engineer", "Java Redis", "Java,Redis")
        );
        AnalysisTask task = taskRepository.saveAndFlush(
            new AnalysisTask(resume.getId(), job.getId())
        );
        AnalysisOutboxEvent event = outboxRepository.saveAndFlush(
            AnalysisOutboxEvent.analysisRequested(task.getId())
        );
        Clock firstAttemptClock = Clock.fixed(
            Instant.parse("2026-08-13T00:00:00Z"),
            ZoneOffset.UTC
        );
        AnalysisOutboxPublisher terminatingPublisher = terminatingPublisherAfterBrokerAck(
            firstAttemptClock
        );

        assertThatThrownBy(terminatingPublisher::publishPending)
            .isInstanceOf(SimulatedProcessTermination.class)
            .hasMessage("simulated process termination after broker ack");

        AnalysisOutboxEvent leased = outboxRepository.findById(event.getId()).orElseThrow();
        assertThat(leased.getStatus()).isEqualTo(AnalysisOutboxStatus.PROCESSING);
        assertThat(leased.getAttemptCount()).isZero();
        assertThat(leased.getPublishedAt()).isNull();
        assertThat(leased.getLeaseToken()).isNotBlank();
        assertThat(leased.getLeaseUntil()).isEqualTo(
            LocalDateTime.now(firstAttemptClock).plusSeconds(30)
        );

        publisher(
            rabbitTemplate,
            Clock.offset(firstAttemptClock, Duration.ofSeconds(31))
        ).publishPending();

        assertThat(rabbitTemplate.receiveAndConvert(RabbitConfig.ANALYSIS_QUEUE, 5000))
            .isEqualTo(task.getId());
        assertThat(rabbitTemplate.receiveAndConvert(RabbitConfig.ANALYSIS_QUEUE, 5000))
            .isEqualTo(task.getId());
        assertThat(rabbitTemplate.receiveAndConvert(RabbitConfig.ANALYSIS_QUEUE, 250))
            .isNull();
        AnalysisOutboxEvent republished = outboxRepository.findById(event.getId()).orElseThrow();
        assertThat(republished.getStatus()).isEqualTo(AnalysisOutboxStatus.PUBLISHED);
        assertThat(republished.getPublishedAt()).isNotNull();
        assertThat(republished.getLeaseToken()).isNull();
        assertThat(republished.getLeaseUntil()).isNull();

        AnalysisEngine engine = mock(AnalysisEngine.class);
        when(engine.analyze(any())).thenReturn(new AnalysisResult(89, "# Deduplicated report"));
        RunAnalysisUseCase runner = new RunAnalysisUseCase(
            taskService,
            taskRepository,
            resumeRepository,
            jobRepository,
            engine,
            new AnalysisMetrics(new SimpleMeterRegistry()),
            new ModelInputPrivacySanitizer()
        );

        runner.run(task.getId(), false);
        runner.run(task.getId(), true);

        verify(engine, times(1)).analyze(any());
        AnalysisTask completed = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(AnalysisTask.Status.SUCCESS);
        assertThat(completed.getAttemptCount()).isEqualTo(1);
        assertThat(reportRepository.findByTaskId(task.getId()))
            .get()
            .extracting(MatchReport::getMatchScore)
            .isEqualTo(89);
        assertThat(reportRepository.count()).isEqualTo(1);
    }

    private Future<?> publishConcurrently(
        AnalysisOutboxPublisher publisher,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        return executor.submit(() -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to start publisher race");
            }
            publisher.publishPending();
            return null;
        });
    }

    private Future<?> runConcurrently(
        RunAnalysisUseCase runner,
        Long taskId,
        boolean redelivered,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        return executor.submit(() -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to start worker race");
            }
            runner.run(taskId, redelivered);
            return null;
        });
    }

    private HttpServer slowAgent(
        CountDownLatch requestReceived,
        CountDownLatch releaseResponse
    ) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/agent/analyze", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                requestReceived.countDown();
                if (!releaseResponse.await(10, TimeUnit.SECONDS)) {
                    return;
                }
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The timed-out client is expected to close the socket before this response.
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private AnalysisOutboxPublisher publisher(RabbitTemplate template) {
        return publisher(template, Clock.systemDefaultZone());
    }

    private AnalysisOutboxPublisher publisher(RabbitTemplate template, Clock clock) {
        return new AnalysisOutboxPublisher(
            outboxRepository,
            taskService,
            template,
            20,
            10,
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            clock,
            new AnalysisMetrics(new SimpleMeterRegistry()),
            outboxTransactions()
        );
    }

    private AnalysisOutboxPublisher terminatingPublisherAfterBrokerAck(Clock clock) {
        return new AnalysisOutboxPublisher(
            outboxRepository,
            taskService,
            rabbitTemplate,
            20,
            10,
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            clock,
            new AnalysisMetrics(new SimpleMeterRegistry()),
            outboxTransactions()
        ) {
            @Override
            void afterBrokerAck(Long eventId) {
                throw new SimulatedProcessTermination(
                    "simulated process termination after broker ack"
                );
            }
        };
    }

    private TransactionTemplate outboxTransactions() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactions;
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
            RabbitTemplate template = new RabbitTemplate(connectionFactory);
            template.setMandatory(true);
            template.setReturnsCallback(returned -> {
            });
            return template;
        }
    }

    private static final class SimulatedProcessTermination extends Error {
        private SimulatedProcessTermination(String message) {
            super(message);
        }
    }
}

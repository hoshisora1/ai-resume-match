package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisOutboxRepository;
import com.zhulikang.aimatch.analysis.AnalysisSubmissionIdempotencyRecord;
import com.zhulikang.aimatch.analysis.AnalysisSubmissionIdempotencyRepository;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.application.resume.PrepareResumeUseCase;
import com.zhulikang.aimatch.application.resume.PreparedResume;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import com.zhulikang.aimatch.resume.ResumeRepository;
import com.zhulikang.aimatch.security.OwnerId;
import com.zhulikang.aimatch.security.RequestIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IdempotentAnalysisSubmissionMySqlIT {
    private static final String IDEMPOTENCY_KEY = "mysql-concurrent-request-1";
    private static final PreparedResume PREPARED_RESUME =
        new PreparedResume("resume.pdf", "Java Redis");

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("ai_resume_match")
        .withUsername("test")
        .withPassword("test");

    @Autowired
    CreateAnalysisSubmissionUseCase createUseCase;
    @Autowired
    AnalysisSubmissionIdempotencyRepository idempotencyRepository;
    @Autowired
    AnalysisOutboxRepository outboxRepository;
    @Autowired
    AnalysisTaskRepository taskRepository;
    @Autowired
    JobDescriptionRepository jobRepository;
    @Autowired
    ResumeRepository resumeRepository;
    @MockBean
    PrepareResumeUseCase prepareResumeUseCase;
    @MockBean
    AnalysisMetrics metrics;
    @MockBean
    RabbitTemplate rabbitTemplate;
    @MockBean
    StringRedisTemplate redisTemplate;

    private ExecutorService executor;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("analysis.scheduling.enabled", () -> "false");
    }

    @BeforeEach
    void setUp() {
        idempotencyRepository.deleteAll();
        outboxRepository.deleteAll();
        taskRepository.deleteAll();
        jobRepository.deleteAll();
        resumeRepository.deleteAll();
        executor = Executors.newFixedThreadPool(2);
        reset(prepareResumeUseCase);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void concurrentIdenticalRequestsCrossTheUniqueKeyRaceAndReturnOneSubmission() throws Exception {
        PreparationBarrier barrier = blockBothRequestsAfterInitialLookup();
        MockMultipartFile file = resumeFile();

        Future<SubmissionAttempt> first = submit(file, "Java Redis", barrier.start());
        Future<SubmissionAttempt> second = submit(file, "Java Redis", barrier.start());
        barrier.releaseWhenBothReady();

        List<SubmissionAttempt> attempts = List.of(
            first.get(15, TimeUnit.SECONDS),
            second.get(15, TimeUnit.SECONDS)
        );

        assertThat(attempts).allSatisfy(attempt -> assertThat(attempt.failure()).isNull());
        assertThat(attempts)
            .extracting(attempt -> attempt.submission().task().getId())
            .containsOnly(attempts.getFirst().submission().task().getId());
        assertExactlyOneSubmissionPersisted();
        assertPersistedHashesOnly(idempotencyRepository.findAll().getFirst());
    }

    @Test
    void concurrentDifferentRequestsWithTheSameKeyCommitOneAndRejectTheOther() throws Exception {
        PreparationBarrier barrier = blockBothRequestsAfterInitialLookup();
        MockMultipartFile file = resumeFile();

        Future<SubmissionAttempt> first = submit(file, "Java Redis", barrier.start());
        Future<SubmissionAttempt> second = submit(file, "Java Kafka", barrier.start());
        barrier.releaseWhenBothReady();

        List<SubmissionAttempt> attempts = List.of(
            first.get(15, TimeUnit.SECONDS),
            second.get(15, TimeUnit.SECONDS)
        );

        assertThat(attempts).filteredOn(attempt -> attempt.submission() != null).hasSize(1);
        assertThat(attempts)
            .filteredOn(attempt -> attempt.failure() != null)
            .singleElement()
            .satisfies(attempt -> assertThat(attempt.failure())
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessage(IdempotencyConflictException.MESSAGE));
        assertExactlyOneSubmissionPersisted();
        assertPersistedHashesOnly(idempotencyRepository.findAll().getFirst());
    }

    private PreparationBarrier blockBothRequestsAfterInitialLookup() {
        CountDownLatch bothPreparing = new CountDownLatch(2);
        CountDownLatch releasePreparation = new CountDownLatch(1);
        when(prepareResumeUseCase.prepare(any())).thenAnswer(invocation -> {
            bothPreparing.countDown();
            if (!releasePreparation.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release MySQL race");
            }
            return PREPARED_RESUME;
        });
        return new PreparationBarrier(bothPreparing, releasePreparation, new CountDownLatch(1));
    }

    private Future<SubmissionAttempt> submit(
        MockMultipartFile file,
        String jobContent,
        CountDownLatch start
    ) {
        return executor.submit(() -> {
            if (!start.await(5, TimeUnit.SECONDS)) {
                return SubmissionAttempt.failed(
                    new IllegalStateException("Timed out waiting to start MySQL race")
                );
            }
            RequestIdentity.set(OwnerId.LEGACY);
            try {
                return SubmissionAttempt.succeeded(createUseCase.create(
                    file,
                    "Backend Engineer",
                    jobContent,
                    IDEMPOTENCY_KEY
                ));
            } catch (RuntimeException failure) {
                return SubmissionAttempt.failed(failure);
            } finally {
                RequestIdentity.clear();
            }
        });
    }

    private void assertExactlyOneSubmissionPersisted() {
        assertThat(resumeRepository.count()).isEqualTo(1);
        assertThat(jobRepository.count()).isEqualTo(1);
        assertThat(taskRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
        assertThat(idempotencyRepository.count()).isEqualTo(1);
    }

    private void assertPersistedHashesOnly(AnalysisSubmissionIdempotencyRecord record) {
        assertThat(record.getIdempotencyKeyHash())
            .matches("[0-9a-f]{64}")
            .isNotEqualTo(IDEMPOTENCY_KEY);
        assertThat(record.getRequestFingerprint()).matches("[0-9a-f]{64}");
        assertThat(List.of(record.getIdempotencyKeyHash(), record.getRequestFingerprint()))
            .noneMatch(value -> value.contains("Java") || value.contains("Backend Engineer"));
        assertThat(record.getTaskId()).isNotNull();
    }

    private MockMultipartFile resumeFile() {
        return new MockMultipartFile(
            "file",
            "resume.pdf",
            "application/pdf",
            new byte[] {1, 2, 3}
        );
    }

    private record SubmissionAttempt(AnalysisSubmission submission, RuntimeException failure) {
        static SubmissionAttempt succeeded(AnalysisSubmission submission) {
            return new SubmissionAttempt(submission, null);
        }

        static SubmissionAttempt failed(RuntimeException failure) {
            return new SubmissionAttempt(null, failure);
        }
    }

    private record PreparationBarrier(
        CountDownLatch bothPreparing,
        CountDownLatch releasePreparation,
        CountDownLatch start
    ) {
        void releaseWhenBothReady() throws InterruptedException {
            start.countDown();
            assertThat(bothPreparing.await(10, TimeUnit.SECONDS)).isTrue();
            releasePreparation.countDown();
        }
    }
}

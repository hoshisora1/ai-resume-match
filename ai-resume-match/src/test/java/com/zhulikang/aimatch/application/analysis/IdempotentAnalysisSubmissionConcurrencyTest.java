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
import com.zhulikang.aimatch.security.RequestIdentity;
import com.zhulikang.aimatch.support.RequestOwnerExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.zhulikang.aimatch.support.RequestOwnerExtension.OWNER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest
@ExtendWith(RequestOwnerExtension.class)
class IdempotentAnalysisSubmissionConcurrencyTest {
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

    @BeforeEach
    void setUp() {
        idempotencyRepository.deleteAll();
        outboxRepository.deleteAll();
        taskRepository.deleteAll();
        jobRepository.deleteAll();
        resumeRepository.deleteAll();
        executor = Executors.newFixedThreadPool(2);
        when(prepareResumeUseCase.prepare(any())).thenReturn(
            new PreparedResume("resume.pdf", "Java Redis")
        );
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void concurrentSameRequestCreatesExactlyOneSubmissionAndOutboxEvent() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "resume.pdf",
            "application/pdf",
            new byte[] {1, 2, 3}
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<AnalysisSubmission> first = submitConcurrently(file, ready, start);
        Future<AnalysisSubmission> second = submitConcurrently(file, ready, start);
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        AnalysisSubmission firstResult = first.get(10, TimeUnit.SECONDS);
        AnalysisSubmission secondResult = second.get(10, TimeUnit.SECONDS);

        assertThat(firstResult.task().getId()).isEqualTo(secondResult.task().getId());
        assertThat(resumeRepository.count()).isEqualTo(1);
        assertThat(jobRepository.count()).isEqualTo(1);
        assertThat(taskRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
        assertThat(idempotencyRepository.findAll())
            .singleElement()
            .satisfies(record -> assertPersistedHashesOnly(record, "concurrent-request-1"));
    }

    @Test
    void sameKeyWithDifferentRequestConflictsWithoutCreatingMoreData() {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "resume.pdf",
            "application/pdf",
            new byte[] {1, 2, 3}
        );
        createUseCase.create(file, "Backend Engineer", "Java Redis", "conflicting-request-1");

        assertThatThrownBy(() -> createUseCase.create(
            file,
            "Backend Engineer",
            "Java Kafka",
            "conflicting-request-1"
        )).isInstanceOf(IdempotencyConflictException.class)
            .hasMessage(IdempotencyConflictException.MESSAGE);

        assertThat(resumeRepository.count()).isEqualTo(1);
        assertThat(jobRepository.count()).isEqualTo(1);
        assertThat(taskRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
        assertThat(idempotencyRepository.count()).isEqualTo(1);
    }

    @Test
    void rollsBackReservationAndBusinessDataWhenSubmissionFails() {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "resume.pdf",
            "application/pdf",
            new byte[] {1, 2, 3}
        );
        doThrow(new IllegalStateException("metrics failed")).when(metrics).taskCreated();

        assertThatThrownBy(() -> createUseCase.create(
            file,
            "Backend Engineer",
            "Java Redis",
            "failed-request-1"
        )).isInstanceOf(IllegalStateException.class)
            .hasMessage("metrics failed");

        assertThat(resumeRepository.count()).isZero();
        assertThat(jobRepository.count()).isZero();
        assertThat(taskRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
        assertThat(idempotencyRepository.count()).isZero();
    }

    private Future<AnalysisSubmission> submitConcurrently(
        MockMultipartFile file,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        return executor.submit(() -> {
            RequestIdentity.set(OWNER_ID);
            try {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to start concurrent submission");
                }
                return createUseCase.create(
                    file,
                    "Backend Engineer",
                    "Java Redis",
                    "concurrent-request-1"
                );
            } finally {
                RequestIdentity.clear();
            }
        });
    }

    private void assertPersistedHashesOnly(AnalysisSubmissionIdempotencyRecord record, String rawKey) {
        assertThat(record.getIdempotencyKeyHash()).matches("[0-9a-f]{64}").isNotEqualTo(rawKey);
        assertThat(record.getRequestFingerprint()).matches("[0-9a-f]{64}");
        assertThat(List.of(record.getIdempotencyKeyHash(), record.getRequestFingerprint()))
            .noneMatch(value -> value.contains("Java") || value.contains("Backend Engineer"));
        assertThat(record.getTaskId()).isNotNull();
    }
}

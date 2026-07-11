package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisOutboxRepository;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.application.resume.PreparedResume;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.aop.support.AopUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class AnalysisSubmissionTransactionTest {
    @Autowired
    PersistAnalysisSubmissionUseCase persistUseCase;
    @Autowired
    ResumeRepository resumeRepository;
    @Autowired
    JobDescriptionRepository jobRepository;
    @Autowired
    AnalysisTaskRepository taskRepository;
    @Autowired
    AnalysisOutboxRepository outboxRepository;
    @MockBean
    AnalysisMetrics metrics;
    @MockBean
    RabbitTemplate rabbitTemplate;
    @MockBean
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearRepositories() {
        outboxRepository.deleteAll();
        taskRepository.deleteAll();
        jobRepository.deleteAll();
        resumeRepository.deleteAll();
    }

    @Test
    void rollsBackEntireSubmissionWhenPostPersistenceMetricFails() {
        assertThat(AopUtils.isAopProxy(persistUseCase)).isTrue();
        doThrow(new IllegalStateException("metrics failed"))
            .when(metrics).taskCreated();

        assertThatThrownBy(() -> persistUseCase.persist(
            new PreparedResume("resume.pdf", "Java", "Java"),
            "Backend Engineer",
            "Java"
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("metrics failed");

        assertThat(resumeRepository.count()).isZero();
        assertThat(jobRepository.count()).isZero();
        assertThat(taskRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }
}

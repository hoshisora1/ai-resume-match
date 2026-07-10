package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.application.resume.PreparedResume;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@SpringBootTest
class AnalysisSubmissionTransactionTest {
    @Autowired
    PersistAnalysisSubmissionUseCase persistUseCase;
    @Autowired
    ResumeRepository resumeRepository;
    @Autowired
    JobDescriptionRepository jobRepository;
    @MockBean
    AnalysisTaskCreator taskCreator;
    @MockBean
    RabbitTemplate rabbitTemplate;
    @MockBean
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearRepositories() {
        jobRepository.deleteAll();
        resumeRepository.deleteAll();
    }

    @Test
    void rollsBackResumeAndJobWhenTaskCreationFails() {
        when(taskCreator.create(anyLong(), anyLong()))
            .thenThrow(new IllegalStateException("task persistence failed"));

        assertThatThrownBy(() -> persistUseCase.persist(
            new PreparedResume("resume.pdf", "Java", "Java"),
            "Backend Engineer",
            "Java"
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("task persistence failed");

        assertThat(resumeRepository.count()).isZero();
        assertThat(jobRepository.count()).isZero();
    }
}

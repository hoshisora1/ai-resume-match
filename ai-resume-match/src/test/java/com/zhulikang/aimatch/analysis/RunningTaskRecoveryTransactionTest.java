package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.application.analysis.AnalysisTaskPublisher;
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest
class RunningTaskRecoveryTransactionTest {
    @Autowired
    RunningTaskRecoveryScheduler scheduler;
    @Autowired
    AnalysisTaskRepository taskRepository;
    @Autowired
    AnalysisOutboxRepository outboxRepository;
    @SpyBean
    AnalysisTaskPublisher publisher;
    @MockBean
    AnalysisMetrics metrics;
    @MockBean
    RabbitTemplate rabbitTemplate;
    @MockBean
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearRepositories() {
        reset(publisher);
        outboxRepository.deleteAll();
        taskRepository.deleteAll();
    }

    @Test
    void resetsTaskAndWritesOutboxInOneTransaction() {
        AnalysisTask task = staleRunningTask();

        scheduler.recoverStaleRunningTasks();

        AnalysisTask recovered = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(AnalysisTask.Status.PENDING);
        assertThat(outboxRepository.findAll())
            .singleElement()
            .satisfies(event -> assertThat(event.getAggregateId()).isEqualTo(task.getId()));
    }

    @Test
    void rollsBackTaskResetWhenOutboxWriteFails() {
        AnalysisTask task = staleRunningTask();
        doThrow(new IllegalStateException("outbox unavailable"))
            .when(publisher).publishAfterCommit(task.getId());

        assertThatThrownBy(scheduler::recoverStaleRunningTasks)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("outbox unavailable");

        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
            .isEqualTo(AnalysisTask.Status.RUNNING);
        assertThat(outboxRepository.count()).isZero();
    }

    private AnalysisTask staleRunningTask() {
        AnalysisTask task = new AnalysisTask(1L, 2L);
        task.markRunning();
        ReflectionTestUtils.setField(task, "updatedAt", LocalDateTime.now().minusMinutes(30));
        return taskRepository.saveAndFlush(task);
    }
}

package com.zhulikang.aimatch.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisServiceTest {
    @Test
    void createsTaskAndPublishesTaskIdMessage() {
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        MatchReportRepository reportRepository = mock(MatchReportRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        when(taskRepository.save(any())).thenAnswer(invocation -> {
            AnalysisTask task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", 99L);
            return task;
        });

        AnalysisService service = new AnalysisService(taskRepository, reportRepository, rabbitTemplate);
        AnalysisTask task = service.createTask(1L, 2L);

        assertThat(task.getResumeId()).isEqualTo(1L);
        assertThat(task.getJobDescriptionId()).isEqualTo(2L);
        verify(rabbitTemplate).convertAndSend(RabbitConfig.ANALYSIS_QUEUE, 99L);
        verify(rabbitTemplate, never()).convertAndSend(RabbitConfig.ANALYSIS_QUEUE, task);
    }
}

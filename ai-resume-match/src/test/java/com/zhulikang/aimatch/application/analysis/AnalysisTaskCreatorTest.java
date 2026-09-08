package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import com.zhulikang.aimatch.support.RequestOwnerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(RequestOwnerExtension.class)
class AnalysisTaskCreatorTest {
    @Test
    void savesTaskPublishesOutboxEventAndRecordsMetric() {
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        AnalysisTaskPublisher publisher = mock(AnalysisTaskPublisher.class);
        AnalysisMetrics metrics = mock(AnalysisMetrics.class);
        AnalysisTaskCreator creator = new AnalysisTaskCreator(taskRepository, publisher, metrics);
        AnalysisTask saved = new AnalysisTask(1L, 2L);
        ReflectionTestUtils.setField(saved, "id", 99L);
        when(taskRepository.save(any(AnalysisTask.class))).thenReturn(saved);

        AnalysisTask result = creator.create(1L, 2L);

        assertThat(result).isSameAs(saved);
        ArgumentCaptor<AnalysisTask> taskCaptor = ArgumentCaptor.forClass(AnalysisTask.class);
        verify(taskRepository).save(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getResumeId()).isEqualTo(1L);
        assertThat(taskCaptor.getValue().getJobDescriptionId()).isEqualTo(2L);
        verify(publisher).publishAfterCommit(99L);
        verify(metrics).taskCreated();
    }
}

package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisFailureCode;
import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RetryAnalysisTaskUseCaseTest {
    @Test
    void retriesRetryableFailedTaskAndPublishesIt() {
        AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
        AnalysisTaskPublisher publisher = mock(AnalysisTaskPublisher.class);
        RetryAnalysisTaskUseCase useCase = new RetryAnalysisTaskUseCase(repository, publisher);
        AnalysisTask task = new AnalysisTask(1L, 2L);
        ReflectionTestUtils.setField(task, "id", 99L);
        task.markRunning();
        task.markRetryableFailure(AnalysisFailureCode.AI_UNAVAILABLE, "AI unavailable");
        when(repository.findById(99L)).thenReturn(Optional.of(task));
        when(repository.save(task)).thenReturn(task);

        AnalysisTask retried = useCase.retry(99L);

        assertThat(retried.getStatus()).isEqualTo(AnalysisTask.Status.PENDING);
        verify(repository).save(task);
        verify(publisher).publishAfterCommit(99L);
    }

    @Test
    void rejectsMissingTask() {
        AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
        AnalysisTaskPublisher publisher = mock(AnalysisTaskPublisher.class);
        RetryAnalysisTaskUseCase useCase = new RetryAnalysisTaskUseCase(repository, publisher);
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.retry(404L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("Analysis task not found");
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsFinalFailureWithoutPublishing() {
        AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
        AnalysisTaskPublisher publisher = mock(AnalysisTaskPublisher.class);
        RetryAnalysisTaskUseCase useCase = new RetryAnalysisTaskUseCase(repository, publisher);
        AnalysisTask task = new AnalysisTask(1L, 2L);
        task.markRunning();
        task.markFinalFailure(AnalysisFailureCode.SOURCE_DATA_MISSING, "Analysis source data is missing");
        when(repository.findById(99L)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> useCase.retry(99L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Only retryable failed analysis tasks can be retried");
        verifyNoInteractions(publisher);
    }
}

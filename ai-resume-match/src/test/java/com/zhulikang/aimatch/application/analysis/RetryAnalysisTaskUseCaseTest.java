package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisFailureCode;
import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.api.ResourceNotFoundException;
import com.zhulikang.aimatch.support.RequestOwnerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static com.zhulikang.aimatch.support.RequestOwnerExtension.OWNER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(RequestOwnerExtension.class)
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
        when(repository.findByIdAndOwnerId(99L, OWNER_ID)).thenReturn(Optional.of(task));
        when(repository.save(task)).thenReturn(task);

        AnalysisTask retried = useCase.retry(99L);

        assertThat(retried.getStatus()).isEqualTo(AnalysisTask.Status.PENDING);
        verify(repository).save(task);
        verify(publisher).publishAfterCommit(99L);
    }

    @Test
    void manuallyRetriesDeliveryFailureAndWritesANewOutboxEvent() {
        AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
        AnalysisTaskPublisher publisher = mock(AnalysisTaskPublisher.class);
        RetryAnalysisTaskUseCase useCase = new RetryAnalysisTaskUseCase(repository, publisher);
        AnalysisTask task = new AnalysisTask(1L, 2L);
        ReflectionTestUtils.setField(task, "id", 99L);
        ReflectionTestUtils.setField(task, "status", AnalysisTask.Status.FAILED_RETRYABLE);
        ReflectionTestUtils.setField(task, "failureCode", AnalysisFailureCode.DELIVERY_FAILED);
        ReflectionTestUtils.setField(task, "failureMessage", "Analysis task could not be delivered");
        when(repository.findByIdAndOwnerId(99L, OWNER_ID)).thenReturn(Optional.of(task));
        when(repository.save(task)).thenReturn(task);

        AnalysisTask retried = useCase.retry(99L);

        assertThat(retried.getStatus()).isEqualTo(AnalysisTask.Status.PENDING);
        assertThat(retried.getFailureCode()).isNull();
        verify(repository).save(task);
        verify(publisher).publishAfterCommit(99L);
    }

    @Test
    void rejectsMissingTask() {
        AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
        AnalysisTaskPublisher publisher = mock(AnalysisTaskPublisher.class);
        RetryAnalysisTaskUseCase useCase = new RetryAnalysisTaskUseCase(repository, publisher);
        when(repository.findByIdAndOwnerId(404L, OWNER_ID)).thenReturn(Optional.empty());

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
        when(repository.findByIdAndOwnerId(99L, OWNER_ID)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> useCase.retry(99L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Only retryable failed analysis tasks can be retried");
        verifyNoInteractions(publisher);
    }
}

package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.analysis.MatchReportRepository;
import com.zhulikang.aimatch.support.RequestOwnerExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static com.zhulikang.aimatch.support.RequestOwnerExtension.OWNER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(RequestOwnerExtension.class)
class GetAnalysisSummaryUseCaseTest {
    private AnalysisTaskRepository taskRepository;
    private MatchReportRepository reportRepository;
    private GetAnalysisSummaryUseCase useCase;

    @BeforeEach
    void setUp() {
        taskRepository = mock(AnalysisTaskRepository.class);
        reportRepository = mock(MatchReportRepository.class);
        useCase = new GetAnalysisSummaryUseCase(taskRepository, reportRepository);
    }

    @Test
    void calculatesOwnerSummary() {
        when(taskRepository.countByOwnerId(OWNER_ID)).thenReturn(12L);
        when(taskRepository.countByOwnerIdAndStatus(OWNER_ID, AnalysisTask.Status.SUCCESS)).thenReturn(10L);
        when(taskRepository.countByOwnerIdAndStatusIn(OWNER_ID, List.of(
            AnalysisTask.Status.PENDING,
            AnalysisTask.Status.RUNNING
        ))).thenReturn(1L);
        when(taskRepository.countByOwnerIdAndStatus(OWNER_ID, AnalysisTask.Status.FAILED_RETRYABLE)).thenReturn(1L);
        when(reportRepository.averageMatchScoreByOwnerId(OWNER_ID)).thenReturn(82.35);

        AnalysisSummary summary = useCase.get();

        assertThat(summary.totalCount()).isEqualTo(12);
        assertThat(summary.successCount()).isEqualTo(10);
        assertThat(summary.inProgressCount()).isEqualTo(1);
        assertThat(summary.retryableFailureCount()).isEqualTo(1);
        assertThat(summary.averageMatchScore()).isEqualByComparingTo("82.4");
        verify(taskRepository).countByOwnerIdAndStatusIn(OWNER_ID, List.of(
            AnalysisTask.Status.PENDING,
            AnalysisTask.Status.RUNNING
        ));
    }

    @Test
    void returnsNullAverageWhenNoReportsExist() {
        when(reportRepository.averageMatchScoreByOwnerId(OWNER_ID)).thenReturn(null);

        AnalysisSummary summary = useCase.get();

        assertThat(summary.averageMatchScore()).isNull();
    }
}

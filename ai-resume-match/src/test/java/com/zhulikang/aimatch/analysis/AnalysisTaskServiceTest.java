package com.zhulikang.aimatch.analysis;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AnalysisTaskServiceTest {
    @Test
    void completesSuccessBySavingReportAndUpdatingTaskInOneServiceCall() {
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        MatchReportRepository reportRepository = mock(MatchReportRepository.class);
        AnalysisTaskService service = new AnalysisTaskService(taskRepository, reportRepository, Duration.ofMinutes(15));
        MatchReport report = new MatchReport(99L, 88, "report");

        service.completeSuccess(report);

        InOrder inOrder = inOrder(reportRepository, taskRepository);
        inOrder.verify(reportRepository).save(report);
        inOrder.verify(taskRepository).markSuccess(
            eq(99L),
            eq(AnalysisTask.Status.SUCCESS),
            any()
        );
    }

    @Test
    void marksRetryableFailureWithFailureCodeAndMessage() {
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        MatchReportRepository reportRepository = mock(MatchReportRepository.class);
        AnalysisTaskService service = new AnalysisTaskService(taskRepository, reportRepository, Duration.ofMinutes(15));

        service.markRetryableFailure(99L, AnalysisFailureCode.AI_UNAVAILABLE, "AI unavailable");

        verify(taskRepository).markFailure(
            eq(99L),
            eq(AnalysisTask.Status.FAILED_RETRYABLE),
            eq(AnalysisFailureCode.AI_UNAVAILABLE),
            eq("AI unavailable"),
            any()
        );
    }

    @Test
    void marksFinalFailureWithFailureCodeAndMessage() {
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        MatchReportRepository reportRepository = mock(MatchReportRepository.class);
        AnalysisTaskService service = new AnalysisTaskService(taskRepository, reportRepository, Duration.ofMinutes(15));

        service.markFinalFailure(99L, AnalysisFailureCode.SOURCE_DATA_MISSING, "Analysis source data is missing");

        verify(taskRepository).markFailure(
            eq(99L),
            eq(AnalysisTask.Status.FAILED_FINAL),
            eq(AnalysisFailureCode.SOURCE_DATA_MISSING),
            eq("Analysis source data is missing"),
            any()
        );
    }
}

package com.zhulikang.aimatch.analysis;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

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
        inOrder.verify(taskRepository).updateStatus(
            org.mockito.ArgumentMatchers.eq(99L),
            org.mockito.ArgumentMatchers.eq(AnalysisTask.Status.SUCCESS),
            org.mockito.ArgumentMatchers.any()
        );
    }
}

package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.analysis.MatchReportRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

@Service
public class GetAnalysisSummaryUseCase {
    private final AnalysisTaskRepository taskRepository;
    private final MatchReportRepository reportRepository;

    public GetAnalysisSummaryUseCase(
        AnalysisTaskRepository taskRepository,
        MatchReportRepository reportRepository
    ) {
        this.taskRepository = taskRepository;
        this.reportRepository = reportRepository;
    }

    public AnalysisSummary get() {
        long total = taskRepository.count();
        long success = taskRepository.countByStatus(AnalysisTask.Status.SUCCESS);
        long inProgress = taskRepository.countByStatusIn(List.of(
            AnalysisTask.Status.PENDING,
            AnalysisTask.Status.RUNNING
        ));
        long retryableFailure = taskRepository.countByStatus(AnalysisTask.Status.FAILED_RETRYABLE);
        BigDecimal average = Optional.ofNullable(reportRepository.averageMatchScore())
            .map(BigDecimal::valueOf)
            .map(value -> value.setScale(1, RoundingMode.HALF_UP))
            .orElse(null);
        return new AnalysisSummary(total, success, inProgress, retryableFailure, average);
    }
}

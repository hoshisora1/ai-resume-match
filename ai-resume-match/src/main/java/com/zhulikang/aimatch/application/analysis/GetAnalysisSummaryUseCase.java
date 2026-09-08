package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.analysis.MatchReportRepository;
import com.zhulikang.aimatch.security.RequestIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional(readOnly = true)
    public AnalysisSummary get() {
        String ownerId = RequestIdentity.currentOwnerId();
        long total = taskRepository.countByOwnerId(ownerId);
        long success = taskRepository.countByOwnerIdAndStatus(ownerId, AnalysisTask.Status.SUCCESS);
        long inProgress = taskRepository.countByOwnerIdAndStatusIn(ownerId, List.of(
            AnalysisTask.Status.PENDING,
            AnalysisTask.Status.RUNNING
        ));
        long retryableFailure = taskRepository.countByOwnerIdAndStatus(ownerId, AnalysisTask.Status.FAILED_RETRYABLE);
        Double averageValue = reportRepository.averageMatchScoreByOwnerId(ownerId);
        BigDecimal average = Optional.ofNullable(averageValue)
            .map(BigDecimal::valueOf)
            .map(value -> value.setScale(1, RoundingMode.HALF_UP))
            .orElse(null);
        return new AnalysisSummary(total, success, inProgress, retryableFailure, average);
    }
}

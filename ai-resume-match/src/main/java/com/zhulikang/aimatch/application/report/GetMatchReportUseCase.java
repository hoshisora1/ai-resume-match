package com.zhulikang.aimatch.application.report;

import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.analysis.MatchReportRepository;
import com.zhulikang.aimatch.analysis.MatchReportView;
import com.zhulikang.aimatch.analysis.ReportCache;
import com.zhulikang.aimatch.security.RequestIdentity;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class GetMatchReportUseCase {
    private final MatchReportRepository reportRepository;
    private final ReportCache reportCache;
    private final AnalysisTaskRepository taskRepository;

    public GetMatchReportUseCase(
        MatchReportRepository reportRepository,
        ReportCache reportCache,
        AnalysisTaskRepository taskRepository
    ) {
        this.reportRepository = reportRepository;
        this.reportCache = reportCache;
        this.taskRepository = taskRepository;
    }

    public Optional<MatchReportView> find(Long taskId) {
        String ownerId = RequestIdentity.currentOwnerId();
        if (!taskRepository.existsByIdAndOwnerId(taskId, ownerId)) {
            return Optional.empty();
        }
        Optional<MatchReportView> cached = reportCache.get(taskId);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<MatchReportView> report = reportRepository.findByTaskId(taskId)
            .map(MatchReportView::from);
        report.ifPresent(reportCache::put);
        return report;
    }
}

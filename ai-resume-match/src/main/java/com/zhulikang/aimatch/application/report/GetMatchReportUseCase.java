package com.zhulikang.aimatch.application.report;

import com.zhulikang.aimatch.analysis.MatchReportRepository;
import com.zhulikang.aimatch.analysis.MatchReportView;
import com.zhulikang.aimatch.analysis.ReportCache;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class GetMatchReportUseCase {
    private final MatchReportRepository reportRepository;
    private final ReportCache reportCache;

    public GetMatchReportUseCase(MatchReportRepository reportRepository, ReportCache reportCache) {
        this.reportRepository = reportRepository;
        this.reportCache = reportCache;
    }

    public Optional<MatchReportView> find(Long taskId) {
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

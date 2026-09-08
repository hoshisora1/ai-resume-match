package com.zhulikang.aimatch.application.report;

import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.analysis.MatchReport;
import com.zhulikang.aimatch.analysis.MatchReportRepository;
import com.zhulikang.aimatch.analysis.MatchReportView;
import com.zhulikang.aimatch.analysis.ReportCache;
import com.zhulikang.aimatch.support.RequestOwnerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.zhulikang.aimatch.support.RequestOwnerExtension.OWNER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(RequestOwnerExtension.class)
class GetMatchReportUseCaseTest {
    @Test
    void returnsCachedReportAfterCheckingOwnershipWithoutLoadingReportFromDatabase() {
        MatchReportRepository repository = mock(MatchReportRepository.class);
        ReportCache reportCache = mock(ReportCache.class);
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        when(taskRepository.existsByIdAndOwnerId(99L, OWNER_ID)).thenReturn(true);
        GetMatchReportUseCase useCase = new GetMatchReportUseCase(repository, reportCache, taskRepository);
        MatchReportView cached = new MatchReportView(99L, 88, "cached", LocalDateTime.now());
        when(reportCache.get(99L)).thenReturn(Optional.of(cached));

        assertThat(useCase.find(99L)).contains(cached);
        verifyNoInteractions(repository);
    }

    @Test
    void storesDatabaseReportInCacheWhenCacheMisses() {
        MatchReportRepository repository = mock(MatchReportRepository.class);
        ReportCache reportCache = mock(ReportCache.class);
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        when(taskRepository.existsByIdAndOwnerId(99L, OWNER_ID)).thenReturn(true);
        GetMatchReportUseCase useCase = new GetMatchReportUseCase(repository, reportCache, taskRepository);
        MatchReport report = new MatchReport(99L, 88, "database");
        MatchReportView expected = MatchReportView.from(report);
        when(reportCache.get(99L)).thenReturn(Optional.empty());
        when(repository.findByTaskId(99L)).thenReturn(Optional.of(report));

        assertThat(useCase.find(99L)).contains(expected);
        verify(reportCache).put(expected);
    }
}

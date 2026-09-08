package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.analysis.MatchReport;
import com.zhulikang.aimatch.analysis.MatchReportRepository;
import com.zhulikang.aimatch.analysis.ReportCache;
import com.zhulikang.aimatch.application.report.GetMatchReportUseCase;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import com.zhulikang.aimatch.security.RequestIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DataJpaTest
class AnonymousOwnershipIsolationTest {
    private static final String OWNER_A = "a".repeat(64);

    @Autowired
    ResumeRepository resumeRepository;
    @Autowired
    JobDescriptionRepository jobRepository;
    @Autowired
    AnalysisTaskRepository taskRepository;
    @Autowired
    MatchReportRepository reportRepository;

    @AfterEach
    void clearRequestIdentity() {
        RequestIdentity.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "0000000000000000000000000000000000000000000000000000000000000000"
    })
    void scopesHistorySummaryDetailsAndReportBeforeCacheLookup(String requestingOwner) {
        AnalysisTask taskA = saveTask(OWNER_A, "a.pdf", "Owner A role", 91);
        AnalysisTask taskB = saveTask(requestingOwner, "b.pdf", "Owner B role", 42);

        ListAnalysisTasksUseCase listUseCase = new ListAnalysisTasksUseCase(
            taskRepository,
            resumeRepository,
            jobRepository,
            reportRepository
        );
        GetAnalysisTaskUseCase taskUseCase = new GetAnalysisTaskUseCase(
            taskRepository,
            resumeRepository,
            jobRepository,
            reportRepository
        );
        GetAnalysisSummaryUseCase summaryUseCase = new GetAnalysisSummaryUseCase(
            taskRepository,
            reportRepository
        );
        ReportCache reportCache = mock(ReportCache.class);
        when(reportCache.get(taskB.getId())).thenReturn(Optional.empty());
        GetMatchReportUseCase reportUseCase = new GetMatchReportUseCase(
            reportRepository,
            reportCache,
            taskRepository
        );

        RequestIdentity.set(requestingOwner);

        AnalysisPage page = listUseCase.list(null, 0, 20);
        assertThat(page.items())
            .singleElement()
            .satisfies(item -> {
                assertThat(item.taskId()).isEqualTo(taskB.getId());
                assertThat(item.jobTitle()).isEqualTo("Owner B role");
                assertThat(item.resumeFileName()).isEqualTo("b.pdf");
            });
        assertThat(taskUseCase.find(taskB.getId())).isPresent();
        assertThat(taskUseCase.find(taskA.getId())).isEmpty();
        assertThat(reportUseCase.find(taskA.getId())).isEmpty();
        verifyNoInteractions(reportCache);

        AnalysisSummary summary = summaryUseCase.get();
        assertThat(summary.totalCount()).isEqualTo(1);
        assertThat(summary.averageMatchScore()).isEqualByComparingTo(new BigDecimal("42.0"));
    }

    private AnalysisTask saveTask(String ownerId, String fileName, String jobTitle, int score) {
        Resume resume = resumeRepository.save(new Resume(ownerId, fileName, "Java"));
        JobDescription job = jobRepository.save(
            new JobDescription(ownerId, jobTitle, "Java", "Java")
        );
        AnalysisTask task = taskRepository.save(new AnalysisTask(ownerId, resume.getId(), job.getId()));
        reportRepository.save(new MatchReport(task.getId(), score, "score=" + score));
        return task;
    }
}

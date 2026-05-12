package com.zhulikang.aimatch.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalysisServiceTest {
    @Test
    void createsTaskAndPublishesTaskIdMessageAfterCommit() {
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        MatchReportRepository reportRepository = mock(MatchReportRepository.class);
        ReportCache reportCache = mock(ReportCache.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        when(taskRepository.save(any())).thenAnswer(invocation -> {
            AnalysisTask task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", 99L);
            return task;
        });

        TransactionSynchronizationManager.initSynchronization();
        try {
            AnalysisService service = new AnalysisService(taskRepository, reportRepository, reportCache, rabbitTemplate);
            AnalysisTask task = service.createTask(1L, 2L);

            assertThat(task.getResumeId()).isEqualTo(1L);
            assertThat(task.getJobDescriptionId()).isEqualTo(2L);
            verifyNoInteractions(rabbitTemplate);

            TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

            verify(rabbitTemplate).convertAndSend(
                RabbitConfig.ANALYSIS_EXCHANGE,
                RabbitConfig.ANALYSIS_ROUTING_KEY,
                99L
            );
            verify(rabbitTemplate, never()).convertAndSend(RabbitConfig.ANALYSIS_QUEUE, task);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void findsReportFromCacheWithoutQueryingDatabase() {
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        MatchReportRepository reportRepository = mock(MatchReportRepository.class);
        ReportCache reportCache = mock(ReportCache.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        MatchReportView cached = new MatchReportView(99L, 88, "cached report", LocalDateTime.now());
        when(reportCache.get(99L)).thenReturn(java.util.Optional.of(cached));

        AnalysisService service = new AnalysisService(taskRepository, reportRepository, reportCache, rabbitTemplate);

        assertThat(service.findReport(99L)).contains(cached);
        verifyNoInteractions(reportRepository);
    }

    @Test
    void storesDatabaseReportInCacheWhenCacheMisses() {
        AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
        MatchReportRepository reportRepository = mock(MatchReportRepository.class);
        ReportCache reportCache = mock(ReportCache.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        MatchReport report = new MatchReport(99L, 88, "database report");
        when(reportCache.get(99L)).thenReturn(java.util.Optional.empty());
        when(reportRepository.findByTaskId(99L)).thenReturn(java.util.Optional.of(report));

        AnalysisService service = new AnalysisService(taskRepository, reportRepository, reportCache, rabbitTemplate);

        assertThat(service.findReport(99L))
            .hasValueSatisfying(view -> assertThat(view.reportContent()).isEqualTo("database report"));
        verify(reportCache).put(MatchReportView.from(report));
    }
}

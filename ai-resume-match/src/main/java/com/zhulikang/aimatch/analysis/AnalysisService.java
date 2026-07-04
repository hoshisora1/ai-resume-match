package com.zhulikang.aimatch.analysis;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

@Service
public class AnalysisService {
    private final AnalysisTaskRepository taskRepository;
    private final MatchReportRepository reportRepository;
    private final ReportCache reportCache;
    private final RabbitTemplate rabbitTemplate;

    public AnalysisService(
        AnalysisTaskRepository taskRepository,
        MatchReportRepository reportRepository,
        ReportCache reportCache,
        RabbitTemplate rabbitTemplate
    ) {
        this.taskRepository = taskRepository;
        this.reportRepository = reportRepository;
        this.reportCache = reportCache;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Transactional
    public AnalysisTask createTask(Long resumeId, Long jobDescriptionId) {
        AnalysisTask task = taskRepository.save(new AnalysisTask(resumeId, jobDescriptionId));
        publishAfterCommit(task.getId());
        return task;
    }

    public Optional<AnalysisTask> findTask(Long taskId) {
        return taskRepository.findById(taskId);
    }

    public Optional<MatchReportView> findReport(Long taskId) {
        Optional<MatchReportView> cached = reportCache.get(taskId);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<MatchReportView> report = reportRepository.findByTaskId(taskId)
            .map(MatchReportView::from);
        report.ifPresent(reportCache::put);
        return report;
    }

    private void publishAfterCommit(Long taskId) {
        Runnable publish = () -> rabbitTemplate.convertAndSend(
            RabbitConfig.ANALYSIS_EXCHANGE,
            RabbitConfig.ANALYSIS_ROUTING_KEY,
            taskId
        );
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish.run();
            }
        });
    }
}

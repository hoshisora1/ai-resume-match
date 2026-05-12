package com.zhulikang.aimatch.analysis;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AnalysisService {
    private final AnalysisTaskRepository taskRepository;
    private final MatchReportRepository reportRepository;
    private final RabbitTemplate rabbitTemplate;

    public AnalysisService(
        AnalysisTaskRepository taskRepository,
        MatchReportRepository reportRepository,
        RabbitTemplate rabbitTemplate
    ) {
        this.taskRepository = taskRepository;
        this.reportRepository = reportRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Transactional
    public AnalysisTask createTask(Long resumeId, Long jobDescriptionId) {
        AnalysisTask task = taskRepository.save(new AnalysisTask(resumeId, jobDescriptionId));
        rabbitTemplate.convertAndSend(RabbitConfig.ANALYSIS_QUEUE, task.getId());
        return task;
    }

    public Optional<MatchReport> findReport(Long taskId) {
        return reportRepository.findByTaskId(taskId);
    }
}

package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.observability.AnalysisMetrics;
import org.springframework.stereotype.Component;

@Component
public class AnalysisTaskCreator {
    private final AnalysisTaskRepository taskRepository;
    private final AnalysisTaskPublisher publisher;
    private final AnalysisMetrics metrics;

    public AnalysisTaskCreator(
        AnalysisTaskRepository taskRepository,
        AnalysisTaskPublisher publisher,
        AnalysisMetrics metrics
    ) {
        this.taskRepository = taskRepository;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    public AnalysisTask create(Long resumeId, Long jobDescriptionId) {
        AnalysisTask task = taskRepository.save(new AnalysisTask(resumeId, jobDescriptionId));
        publisher.publishAfterCommit(task.getId());
        metrics.taskCreated();
        return task;
    }
}

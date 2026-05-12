package com.zhulikang.aimatch.analysis;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisTaskService {
    private final AnalysisTaskRepository taskRepository;

    public AnalysisTaskService(AnalysisTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Transactional
    public boolean tryStart(Long taskId) {
        return taskRepository.markRunningIfPending(
            taskId,
            AnalysisTask.Status.RUNNING,
            AnalysisTask.Status.PENDING
        ) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSuccess(Long taskId) {
        taskRepository.updateStatus(taskId, AnalysisTask.Status.SUCCESS);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long taskId) {
        taskRepository.updateStatus(taskId, AnalysisTask.Status.FAILED);
    }
}

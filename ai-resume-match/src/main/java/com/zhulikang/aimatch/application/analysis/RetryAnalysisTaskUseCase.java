package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import com.zhulikang.aimatch.api.ResourceNotFoundException;
import com.zhulikang.aimatch.security.RequestIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetryAnalysisTaskUseCase {
    private final AnalysisTaskRepository taskRepository;
    private final AnalysisTaskPublisher publisher;

    public RetryAnalysisTaskUseCase(AnalysisTaskRepository taskRepository, AnalysisTaskPublisher publisher) {
        this.taskRepository = taskRepository;
        this.publisher = publisher;
    }

    @Transactional
    public AnalysisTask retry(Long taskId) {
        String ownerId = RequestIdentity.currentOwnerId();
        AnalysisTask task = taskRepository.findByIdAndOwnerId(taskId, ownerId)
            .orElseThrow(() -> new ResourceNotFoundException("Analysis task not found"));
        try {
            task.retry();
        } catch (IllegalStateException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
        AnalysisTask saved = taskRepository.save(task);
        publisher.publishAfterCommit(saved.getId());
        return saved;
    }
}

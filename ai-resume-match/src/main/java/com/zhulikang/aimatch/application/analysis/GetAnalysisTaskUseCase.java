package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.AnalysisTaskRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class GetAnalysisTaskUseCase {
    private final AnalysisTaskRepository taskRepository;

    public GetAnalysisTaskUseCase(AnalysisTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    public Optional<AnalysisTask> find(Long taskId) {
        return taskRepository.findById(taskId);
    }
}

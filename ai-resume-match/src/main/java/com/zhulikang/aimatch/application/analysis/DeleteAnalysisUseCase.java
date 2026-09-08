package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisDataDeletionService;
import com.zhulikang.aimatch.api.ResourceNotFoundException;
import com.zhulikang.aimatch.security.RequestIdentity;
import org.springframework.stereotype.Service;

@Service
public class DeleteAnalysisUseCase {
    private final AnalysisDataDeletionService deletionService;

    public DeleteAnalysisUseCase(AnalysisDataDeletionService deletionService) {
        this.deletionService = deletionService;
    }

    public void delete(Long taskId) {
        if (!deletionService.deleteOwned(taskId, RequestIdentity.currentOwnerId())) {
            throw new ResourceNotFoundException("Analysis task not found");
        }
    }
}

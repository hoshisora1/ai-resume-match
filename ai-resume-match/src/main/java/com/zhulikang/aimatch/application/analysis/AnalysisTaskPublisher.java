package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisOutboxEvent;
import com.zhulikang.aimatch.analysis.AnalysisOutboxRepository;
import com.zhulikang.aimatch.observability.RequestCorrelation;
import com.zhulikang.aimatch.observability.TraceContextHeaders;
import org.springframework.stereotype.Component;

@Component
public class AnalysisTaskPublisher {
    private final AnalysisOutboxRepository outboxRepository;

    public AnalysisTaskPublisher(AnalysisOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    public void publishAfterCommit(Long taskId) {
        outboxRepository.save(AnalysisOutboxEvent.analysisRequested(
            taskId,
            RequestCorrelation.currentCorrelationIdOrNew(),
            TraceContextHeaders.captureCurrentTraceParent()
        ));
    }
}

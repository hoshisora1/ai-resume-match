package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.application.analysis.RunAnalysisUseCase;
import com.zhulikang.aimatch.observability.RequestCorrelation;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class AnalysisWorker {
    private final RunAnalysisUseCase runAnalysisUseCase;

    public AnalysisWorker(RunAnalysisUseCase runAnalysisUseCase) {
        this.runAnalysisUseCase = runAnalysisUseCase;
    }

    public void handle(Long taskId) {
        handle(taskId, false, null);
    }

    @RabbitListener(queues = RabbitConfig.ANALYSIS_QUEUE)
    public void handle(
        Long taskId,
        @Header(name = AmqpHeaders.REDELIVERED, required = false) Boolean redelivered,
        @Header(name = RequestCorrelation.CORRELATION_ID_HEADER, required = false) String correlationId
    ) {
        String safeCorrelationId = RequestCorrelation.safeOrNew(correlationId);
        RequestCorrelation.put(null, safeCorrelationId);
        try {
            runAnalysisUseCase.run(taskId, Boolean.TRUE.equals(redelivered));
        } finally {
            RequestCorrelation.clear();
        }
    }

    public void handle(Long taskId, Boolean redelivered) {
        handle(taskId, redelivered, null);
    }
}

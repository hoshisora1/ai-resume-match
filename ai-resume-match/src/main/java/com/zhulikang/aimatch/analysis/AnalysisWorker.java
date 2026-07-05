package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.application.analysis.RunAnalysisUseCase;
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
        handle(taskId, false);
    }

    @RabbitListener(queues = RabbitConfig.ANALYSIS_QUEUE)
    public void handle(Long taskId, @Header(name = AmqpHeaders.REDELIVERED, required = false) Boolean redelivered) {
        runAnalysisUseCase.run(taskId, Boolean.TRUE.equals(redelivered));
    }
}

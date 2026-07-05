package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.RabbitConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class AnalysisTaskPublisher {
    private final RabbitTemplate rabbitTemplate;

    public AnalysisTaskPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishAfterCommit(Long taskId) {
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

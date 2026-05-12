package com.zhulikang.aimatch.analysis;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    public static final String ANALYSIS_QUEUE = "analysis.queue";
    public static final String ANALYSIS_EXCHANGE = "analysis.exchange";
    public static final String ANALYSIS_ROUTING_KEY = "analysis.requested";
    public static final String ANALYSIS_DLX = "analysis.dlx";
    public static final String ANALYSIS_DLQ = "analysis.dlq";

    @Bean
    public DirectExchange analysisExchange() {
        return new DirectExchange(ANALYSIS_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange analysisDeadLetterExchange() {
        return new DirectExchange(ANALYSIS_DLX, true, false);
    }

    @Bean
    public Queue analysisQueue() {
        return QueueBuilder.durable(ANALYSIS_QUEUE)
            .deadLetterExchange(ANALYSIS_DLX)
            .deadLetterRoutingKey(ANALYSIS_DLQ)
            .build();
    }

    @Bean
    public Queue analysisDeadLetterQueue() {
        return QueueBuilder.durable(ANALYSIS_DLQ).build();
    }

    @Bean
    public Binding analysisBinding(
        @Qualifier("analysisQueue") Queue analysisQueue,
        @Qualifier("analysisExchange") DirectExchange analysisExchange
    ) {
        return BindingBuilder.bind(analysisQueue)
            .to(analysisExchange)
            .with(ANALYSIS_ROUTING_KEY);
    }

    @Bean
    public Binding analysisDeadLetterBinding(
        @Qualifier("analysisDeadLetterQueue") Queue analysisDeadLetterQueue,
        @Qualifier("analysisDeadLetterExchange") DirectExchange analysisDeadLetterExchange
    ) {
        return BindingBuilder.bind(analysisDeadLetterQueue)
            .to(analysisDeadLetterExchange)
            .with(ANALYSIS_DLQ);
    }
}

package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.analysis.AnalysisOutboxEvent;
import com.zhulikang.aimatch.analysis.AnalysisOutboxEventType;
import com.zhulikang.aimatch.analysis.AnalysisOutboxRepository;
import com.zhulikang.aimatch.analysis.AnalysisOutboxStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AnalysisTaskPublisherTest {
    @Test
    void persistsAnalysisRequestedEvent() {
        AnalysisOutboxRepository outboxRepository = mock(AnalysisOutboxRepository.class);
        AnalysisTaskPublisher publisher = new AnalysisTaskPublisher(outboxRepository);

        publisher.publishAfterCommit(99L);

        ArgumentCaptor<AnalysisOutboxEvent> eventCaptor = ArgumentCaptor.forClass(AnalysisOutboxEvent.class);
        verify(outboxRepository).save(eventCaptor.capture());
        AnalysisOutboxEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo(AnalysisOutboxEventType.ANALYSIS_REQUESTED);
        assertThat(event.getAggregateType()).isEqualTo("analysis_task");
        assertThat(event.getAggregateId()).isEqualTo(99L);
        assertThat(event.getStatus()).isEqualTo(AnalysisOutboxStatus.PENDING);
        assertThat(event.getPayloadJson()).contains("\"taskId\":99");
    }
}

package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.application.analysis.RunAnalysisUseCase;
import com.zhulikang.aimatch.observability.RequestCorrelation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AnalysisWorkerTest {
    @Test
    void delegatesMessageToRunAnalysisUseCase() {
        RunAnalysisUseCase useCase = mock(RunAnalysisUseCase.class);
        AnalysisWorker worker = new AnalysisWorker(useCase);

        worker.handle(99L, true);

        verify(useCase).run(99L, true);
    }

    @Test
    void restoresCorrelationIdFromRabbitHeader() {
        RunAnalysisUseCase useCase = mock(RunAnalysisUseCase.class);
        AnalysisWorker worker = new AnalysisWorker(useCase);
        doAnswer(invocation -> {
            assertThat(RequestCorrelation.currentCorrelationId()).isEqualTo("correlation-1");
            return null;
        }).when(useCase).run(99L, false);

        worker.handle(99L, false, "correlation-1");

        verify(useCase).run(99L, false);
        assertThat(RequestCorrelation.currentCorrelationId()).isNull();
    }
}

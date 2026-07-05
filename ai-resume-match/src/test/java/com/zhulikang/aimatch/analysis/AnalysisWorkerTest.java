package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.application.analysis.RunAnalysisUseCase;
import org.junit.jupiter.api.Test;

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
}

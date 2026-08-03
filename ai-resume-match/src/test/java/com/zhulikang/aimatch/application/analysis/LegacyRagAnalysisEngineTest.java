package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.ai.AiClient;
import com.zhulikang.aimatch.analysis.ReportParser;
import com.zhulikang.aimatch.rag.HashingEmbeddingClient;
import com.zhulikang.aimatch.rag.RagContextBuilder;
import com.zhulikang.aimatch.rag.TextChunker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyRagAnalysisEngineTest {
    @Test
    void retrievesResumeEvidenceAndParsesLegacyReport() {
        AiClient aiClient = mock(AiClient.class);
        when(aiClient.complete(contains("Redis"))).thenReturn("匹配分数：88\nRedis evidence");
        LegacyRagAnalysisEngine engine = new LegacyRagAnalysisEngine(
            new TextChunker(),
            new HashingEmbeddingClient(),
            new RagContextBuilder(),
            aiClient,
            new ReportParser()
        );

        AnalysisResult result = engine.analyze(new AnalysisInput(
            9L,
            "Java Spring Boot Redis project",
            "Backend Engineer",
            "Need Java and Redis",
            List.of("Java", "Redis"),
            "legacy-test-9"
        ));

        assertThat(result.matchScore()).isEqualTo(88);
        assertThat(result.reportContent()).contains("Redis evidence");
        verify(aiClient).complete(contains("召回的简历片段"));
    }
}

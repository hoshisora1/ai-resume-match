package com.zhulikang.aimatch.application.analysis;

import com.zhulikang.aimatch.ai.AiClient;
import com.zhulikang.aimatch.analysis.ReportParser;
import com.zhulikang.aimatch.rag.EmbeddingClient;
import com.zhulikang.aimatch.rag.InMemoryVectorStore;
import com.zhulikang.aimatch.rag.RagContextBuilder;
import com.zhulikang.aimatch.rag.TextChunker;
import com.zhulikang.aimatch.rag.VectorSearchResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "analysis.engine", havingValue = "legacy", matchIfMissing = true)
public class LegacyRagAnalysisEngine implements AnalysisEngine {
    private final TextChunker textChunker;
    private final EmbeddingClient embeddingClient;
    private final RagContextBuilder ragContextBuilder;
    private final AiClient aiClient;
    private final ReportParser reportParser;

    public LegacyRagAnalysisEngine(
        TextChunker textChunker,
        EmbeddingClient embeddingClient,
        RagContextBuilder ragContextBuilder,
        AiClient aiClient,
        ReportParser reportParser
    ) {
        this.textChunker = textChunker;
        this.embeddingClient = embeddingClient;
        this.ragContextBuilder = ragContextBuilder;
        this.aiClient = aiClient;
        this.reportParser = reportParser;
    }

    @Override
    public AnalysisResult analyze(AnalysisInput input) {
        InMemoryVectorStore vectorStore = new InMemoryVectorStore(embeddingClient);
        vectorStore.addAll(textChunker.chunk(input.resumeText()));
        List<String> retrievedChunks = vectorStore.search(input.jobDescription(), 3).stream()
            .map(VectorSearchResult::text)
            .toList();
        String prompt = ragContextBuilder.build(retrievedChunks, input.jobDescription(), input.skillTags());
        String report = aiClient.complete(prompt);
        return new AnalysisResult(reportParser.extractScore(report), report);
    }
}

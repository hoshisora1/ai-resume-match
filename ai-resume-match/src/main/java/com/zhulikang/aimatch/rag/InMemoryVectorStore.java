package com.zhulikang.aimatch.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class InMemoryVectorStore {
    private final EmbeddingClient embeddingClient;
    private final List<Entry> entries = new ArrayList<>();

    public InMemoryVectorStore(EmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient;
    }

    public void addAll(List<String> chunks) {
        for (String chunk : chunks) {
            if (chunk != null && !chunk.isBlank()) {
                entries.add(new Entry(chunk, embeddingClient.embed(chunk)));
            }
        }
    }

    public List<VectorSearchResult> search(String query, int topK) {
        double[] queryVector = embeddingClient.embed(query);
        return entries.stream()
            .map(entry -> new VectorSearchResult(entry.text(), cosine(queryVector, entry.vector())))
            .sorted(Comparator.comparingDouble(VectorSearchResult::score).reversed())
            .limit(topK)
            .toList();
    }

    private double cosine(double[] left, double[] right) {
        double dot = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
        }
        return dot;
    }

    private record Entry(String text, double[] vector) {
    }
}

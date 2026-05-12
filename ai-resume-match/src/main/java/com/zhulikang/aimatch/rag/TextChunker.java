package com.zhulikang.aimatch.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TextChunker {
    private static final int CHUNK_SIZE = 350;
    private static final int OVERLAP = 80;

    public List<String> chunk(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + CHUNK_SIZE, normalized.length());
            chunks.add(normalized.substring(start, end));
            if (end == normalized.length()) {
                break;
            }
            start = Math.max(0, end - OVERLAP);
        }
        return chunks;
    }
}

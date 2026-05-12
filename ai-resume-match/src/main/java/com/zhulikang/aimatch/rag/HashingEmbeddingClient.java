package com.zhulikang.aimatch.rag;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class HashingEmbeddingClient implements EmbeddingClient {
    private static final int DIMENSION = 128;

    @Override
    public double[] embed(String text) {
        double[] vector = new double[DIMENSION];
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5]+", " ")
            .trim();
        if (normalized.isEmpty()) {
            return vector;
        }

        for (String token : normalized.split("\\s+")) {
            int index = Math.floorMod(token.hashCode(), DIMENSION);
            vector[index] += 1.0;
        }
        normalize(vector);
        return vector;
    }

    private void normalize(double[] vector) {
        double sum = 0.0;
        for (double value : vector) {
            sum += value * value;
        }
        if (sum == 0.0) {
            return;
        }
        double norm = Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }
}

package com.zhulikang.aimatch.rag;

public interface EmbeddingClient {
    double[] embed(String text);
}

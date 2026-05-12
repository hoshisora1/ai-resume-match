package com.zhulikang.aimatch.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryVectorStoreTest {
    private final EmbeddingClient embeddingClient = new HashingEmbeddingClient();

    @Test
    void returnsMostRelevantChunksBySimilarity() {
        InMemoryVectorStore store = new InMemoryVectorStore(embeddingClient);
        store.addAll(List.of(
            "项目：高性能秒杀系统，使用 Redis Kafka MySQL 解决库存扣减和异步下单",
            "教育背景：中国地质大学软件工程专业，学习数据结构和算法",
            "工具：熟悉 Git Maven IntelliJ IDEA"
        ));

        List<VectorSearchResult> results = store.search("高并发 Redis Kafka", 2);

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().text()).contains("Redis Kafka");
        assertThat(results.getFirst().score()).isGreaterThanOrEqualTo(results.get(1).score());
    }
}

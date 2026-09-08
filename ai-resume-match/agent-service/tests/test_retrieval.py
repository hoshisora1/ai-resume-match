import pytest

from agent_service.retrieval import (
    DenseRetriever,
    HashingRetriever,
    HybridRetriever,
    chunk_text,
    chunk_text_with_offsets,
)


class SemanticFakeEmbeddingClient:
    version = "semantic-fake-v1"

    def __init__(self) -> None:
        self.calls: list[list[str]] = []

    def embed(self, texts: list[str]) -> list[list[float]]:
        self.calls.append(texts)
        vectors: list[list[float]] = []
        for text in texts:
            folded = text.casefold()
            if "java" in folded or "jvm" in folded:
                vectors.append([1.0, 0.0, 0.0])
            elif "react" in folded or "interface" in folded or "frontend" in folded:
                vectors.append([0.0, 1.0, 0.0])
            else:
                vectors.append([0.0, 0.0, 1.0])
        return vectors


def test_chunks_long_text_with_overlap() -> None:
    chunks = chunk_text("A" * 1_100, chunk_size=600, overlap=100)

    assert len(chunks) == 2
    assert len(chunks[0]) == 600
    assert chunks[0][-100:] == chunks[1][:100]


def test_chunks_preserve_offsets_in_normalized_source() -> None:
    chunks = chunk_text_with_offsets("  Java backend.\r\n\r\n  Python Agent.  ")

    assert [(chunk.text, chunk.source_start, chunk.source_end) for chunk in chunks] == [
        ("Java backend.", 0, 13),
        ("Python Agent.", 17, 30),
    ]


@pytest.mark.parametrize(
    ("chunk_size", "overlap"),
    [(0, 0), (100, -1), (100, 100)],
)
def test_rejects_invalid_chunk_bounds(chunk_size: int, overlap: int) -> None:
    with pytest.raises(ValueError):
        chunk_text_with_offsets("text", chunk_size=chunk_size, overlap=overlap)


def test_retrieval_prefers_matching_skill_evidence() -> None:
    retriever = HashingRetriever(
        "Java Spring Boot Redis backend project.\n\nReact TypeScript frontend project."
    )

    result = retriever.search("Redis Java backend", 1)

    assert result[0].evidence_id == "resume:0"
    assert "Redis" in result[0].excerpt
    assert result[0].source_start == 0
    assert result[0].source_end == 39


def test_retrieval_returns_empty_for_unrelated_query() -> None:
    retriever = HashingRetriever("Java Spring Boot Redis backend project.")

    result = retriever.search("COBOL mainframe zOS", 3)

    assert result == []


def test_retrieval_drops_barely_related_oversized_chunk() -> None:
    filler = " ".join(f"token{index}" for index in range(400))
    retriever = HashingRetriever(
        f"Java {filler}",
        dimensions=4_096,
    )

    result = retriever.search("Java Python RAG evaluation observability", 3)

    assert result == []


def test_retrieval_matches_chinese_skill_bigrams() -> None:
    retriever = HashingRetriever("设计任务状态机，实现失败重试与幂等处理。\n\n开发响应式前端页面。")

    result = retriever.search("状态机 幂等 重试", 1)

    assert result[0].evidence_id == "resume:0"
    assert "幂等" in result[0].excerpt


def test_dense_retrieval_finds_semantic_synonym_and_caches_vectors() -> None:
    client = SemanticFakeEmbeddingClient()
    retriever = DenseRetriever(
        "Built Java backend APIs.\n\nCreated a React frontend.",
        client,
        min_similarity=0.8,
    )

    first = retriever.search("JVM service", 1)
    second = retriever.search("JVM service", 1)

    assert first[0].evidence_id == "resume:0"
    assert second == first
    assert client.calls == [
        ["Built Java backend APIs.", "Created a React frontend."],
        ["JVM service"],
    ]
    assert retriever.version == "dense-cosine-v1+semantic-fake-v1"


def test_hybrid_retrieval_fuses_lexical_and_dense_rankings() -> None:
    retriever = HybridRetriever(
        "Built Java backend APIs.\n\nCreated a React frontend interface.",
        SemanticFakeEmbeddingClient(),
        dense_min_similarity=0.8,
    )

    semantic = retriever.search("JVM service", 1)
    exact = retriever.search("React frontend", 1)

    assert semantic[0].evidence_id == "resume:0"
    assert exact[0].evidence_id == "resume:1"
    assert 0 < semantic[0].score <= 1
    assert retriever.version == "hybrid-rrf-v1+dense-cosine-v1+semantic-fake-v1"


@pytest.mark.parametrize("top_k", [0, -1])
def test_dense_and_hybrid_reject_non_positive_top_k(top_k: int) -> None:
    client = SemanticFakeEmbeddingClient()

    with pytest.raises(ValueError, match="top_k"):
        DenseRetriever("Java", client).search("Java", top_k)
    with pytest.raises(ValueError, match="top_k"):
        HybridRetriever("Java", client).search("Java", top_k)

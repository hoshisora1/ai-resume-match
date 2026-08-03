from agent_service.retrieval import HashingRetriever, chunk_text


def test_chunks_long_text_with_overlap() -> None:
    chunks = chunk_text("A" * 1_100, chunk_size=600, overlap=100)

    assert len(chunks) == 2
    assert len(chunks[0]) == 600
    assert chunks[0][-100:] == chunks[1][:100]


def test_retrieval_prefers_matching_skill_evidence() -> None:
    retriever = HashingRetriever(
        "Java Spring Boot Redis backend project.\n\nReact TypeScript frontend project."
    )

    result = retriever.search("Redis Java backend", 1)

    assert result[0].evidence_id == "resume:0"
    assert "Redis" in result[0].excerpt


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

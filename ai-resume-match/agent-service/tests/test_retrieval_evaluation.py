import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from agent_service.retrieval_evaluation import (
    RetrievalDataset,
    evaluate_retrieval_dataset,
    load_retrieval_dataset,
)
from agent_service.retrieval import DenseRetriever
from tests.test_retrieval import SemanticFakeEmbeddingClient
from evals.build_retrieval_dataset import build_dataset


def small_dataset() -> RetrievalDataset:
    return RetrievalDataset.model_validate(
        {
            "datasetVersion": "test-v1",
            "documents": [
                {
                    "documentId": "resume-1",
                    "text": "Java backend.\n\nReact frontend.",
                    "spans": [
                        {"spanId": "resume-1:backend", "start": 0, "end": 13},
                        {"spanId": "resume-1:frontend", "start": 15, "end": 30},
                    ],
                }
            ],
            "cases": [
                {
                    "id": "exact",
                    "documentId": "resume-1",
                    "query": "Java",
                    "relevantSpanIds": ["resume-1:backend"],
                    "noRelevantEvidence": False,
                    "tags": ["exact"],
                },
                {
                    "id": "lexical-miss",
                    "documentId": "resume-1",
                    "query": "JVM service",
                    "relevantSpanIds": ["resume-1:backend"],
                    "noRelevantEvidence": False,
                    "tags": ["synonym"],
                },
                {
                    "id": "no-evidence",
                    "documentId": "resume-1",
                    "query": "COBOL",
                    "relevantSpanIds": [],
                    "noRelevantEvidence": True,
                    "tags": ["no-evidence"],
                },
            ],
        }
    )


def test_evaluates_recall_mrr_and_false_positive_rate_without_source_text() -> None:
    summary = evaluate_retrieval_dataset(
        small_dataset(),
        dataset_sha256="a" * 64,
    )

    metrics = summary["metrics"]
    assert metrics["cases"] == 3
    assert metrics["positiveCases"] == 2
    assert metrics["noEvidenceCases"] == 1
    assert metrics["recallAt5"] == 0.5
    assert metrics["mrrAt5"] == 0.5
    assert metrics["falsePositiveRate"] == 0.0
    assert metrics["falsePositiveCases"] == 0
    assert metrics["retrievalP95Ms"] >= 0
    rendered = json.dumps(summary)
    assert "Java backend" not in rendered
    assert "React frontend" not in rendered
    assert "JVM service" not in rendered


def test_evaluates_versioned_non_default_retriever_metadata() -> None:
    client = SemanticFakeEmbeddingClient()

    summary = evaluate_retrieval_dataset(
        small_dataset(),
        dataset_sha256="b" * 64,
        retriever_factory=lambda text: DenseRetriever(text, client, min_similarity=0.8),
        retriever_name="DenseRetriever",
        retriever_parameters={"minimumSimilarity": 0.8, "embeddingModel": "fake"},
    )

    assert summary["retriever"] == {
        "name": "DenseRetriever",
        "version": "dense-cosine-v1+semantic-fake-v1",
        "topK": 5,
        "chunkSize": 600,
        "chunkOverlap": 100,
        "minimumSimilarity": 0.8,
        "embeddingModel": "fake",
    }


def test_dataset_rejects_ambiguous_relevance_label() -> None:
    payload = small_dataset().model_dump(by_alias=True)
    payload["cases"][0]["noRelevantEvidence"] = True

    with pytest.raises(ValidationError, match="relevantSpanIds must be non-empty"):
        RetrievalDataset.model_validate(payload)


def test_dataset_rejects_span_outside_document() -> None:
    payload = small_dataset().model_dump(by_alias=True)
    payload["documents"][0]["spans"][0]["end"] = 999

    with pytest.raises(ValidationError, match="outside the document"):
        RetrievalDataset.model_validate(payload)


def test_repository_dataset_has_versioned_offsets_and_sixty_cases() -> None:
    path = Path(__file__).parents[1] / "evals" / "retrieval_dataset_v1.json"

    dataset, digest = load_retrieval_dataset(path)

    assert json.loads(path.read_text(encoding="utf-8")) == build_dataset()
    assert dataset.dataset_version == "retrieval-qrels-v1"
    assert len(dataset.documents) == 12
    assert len(dataset.cases) == 60
    assert len(digest) == 64
    assert sum(case.no_relevant_evidence for case in dataset.cases) == 16
    assert {tag for case in dataset.cases for tag in case.tags} >= {
        "en",
        "zh",
        "mixed",
        "synonym",
        "negation",
        "long",
        "no-evidence",
    }


def test_dataset_digest_is_independent_of_line_endings(tmp_path: Path) -> None:
    rendered = json.dumps(small_dataset().model_dump(by_alias=True), indent=2)
    lf_path = tmp_path / "lf.json"
    crlf_path = tmp_path / "crlf.json"
    lf_path.write_bytes((rendered + "\n").encode("utf-8"))
    crlf_path.write_bytes((rendered.replace("\n", "\r\n") + "\r\n").encode("utf-8"))

    _, lf_digest = load_retrieval_dataset(lf_path)
    _, crlf_digest = load_retrieval_dataset(crlf_path)

    assert lf_digest == crlf_digest

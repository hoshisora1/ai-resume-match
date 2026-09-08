from __future__ import annotations

import hashlib
import json
import math
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, model_validator

from agent_service.retrieval import (
    DEFAULT_CHUNK_OVERLAP,
    DEFAULT_CHUNK_SIZE,
    DEFAULT_HASHING_DIMENSIONS,
    MIN_RELEVANCE_SCORE,
    HashingRetriever,
    Retriever,
)


class EvaluationModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")


class GoldSpan(EvaluationModel):
    span_id: str = Field(alias="spanId", min_length=1, max_length=120)
    start: int = Field(ge=0)
    end: int = Field(gt=0)


class RetrievalDocument(EvaluationModel):
    document_id: str = Field(alias="documentId", min_length=1, max_length=120)
    text: str = Field(min_length=1, max_length=200_000)
    spans: list[GoldSpan] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_spans(self) -> RetrievalDocument:
        span_ids = [span.span_id for span in self.spans]
        if len(span_ids) != len(set(span_ids)):
            raise ValueError("spanId values must be unique within a document")
        for span in self.spans:
            if span.start >= span.end or span.end > len(self.text):
                raise ValueError(f"span {span.span_id} is outside the document")
            if not self.text[span.start : span.end].strip():
                raise ValueError(f"span {span.span_id} must cover non-whitespace text")
        return self


class RetrievalCase(EvaluationModel):
    case_id: str = Field(alias="id", min_length=1, max_length=120)
    document_id: str = Field(alias="documentId", min_length=1, max_length=120)
    query: str = Field(min_length=1, max_length=240)
    relevant_span_ids: list[str] = Field(alias="relevantSpanIds", default_factory=list)
    no_relevant_evidence: bool = Field(alias="noRelevantEvidence")
    tags: list[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_relevance_label(self) -> RetrievalCase:
        if self.no_relevant_evidence == bool(self.relevant_span_ids):
            raise ValueError(
                "relevantSpanIds must be non-empty exactly when noRelevantEvidence is false"
            )
        if len(self.relevant_span_ids) != len(set(self.relevant_span_ids)):
            raise ValueError("relevantSpanIds must not contain duplicates")
        return self


class RetrievalDataset(EvaluationModel):
    dataset_version: str = Field(alias="datasetVersion", min_length=1, max_length=120)
    documents: list[RetrievalDocument] = Field(min_length=1)
    cases: list[RetrievalCase] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_references(self) -> RetrievalDataset:
        documents = {document.document_id: document for document in self.documents}
        if len(documents) != len(self.documents):
            raise ValueError("documentId values must be unique")
        case_ids = [case.case_id for case in self.cases]
        if len(case_ids) != len(set(case_ids)):
            raise ValueError("case id values must be unique")
        if not any(case.no_relevant_evidence for case in self.cases):
            raise ValueError("dataset must contain at least one no-evidence case")
        if not any(not case.no_relevant_evidence for case in self.cases):
            raise ValueError("dataset must contain at least one positive case")

        for case in self.cases:
            document = documents.get(case.document_id)
            if document is None:
                raise ValueError(f"case {case.case_id} references an unknown document")
            document_span_ids = {span.span_id for span in document.spans}
            unknown = set(case.relevant_span_ids) - document_span_ids
            if unknown:
                raise ValueError(
                    f"case {case.case_id} references unknown spans: {sorted(unknown)}"
                )
        return self


def load_retrieval_dataset(path: Path) -> tuple[RetrievalDataset, str]:
    content = path.read_bytes()
    dataset = RetrievalDataset.model_validate_json(content)
    canonical = json.dumps(
        dataset.model_dump(by_alias=True),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return dataset, hashlib.sha256(canonical).hexdigest()


def _overlaps(start: int, end: int, span: GoldSpan) -> bool:
    return start < span.end and end > span.start


def _percentile_95(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * 0.95) - 1)]


def evaluate_retrieval_dataset(
    dataset: RetrievalDataset,
    *,
    dataset_sha256: str,
    top_k: int = 5,
    retriever_factory: Callable[[str], Retriever] = HashingRetriever,
    retriever_version: str | None = None,
    retriever_name: str = "HashingRetriever",
    retriever_parameters: dict[str, Any] | None = None,
) -> dict[str, Any]:
    if top_k < 1:
        raise ValueError("top_k must be positive")

    documents = {document.document_id: document for document in dataset.documents}
    retrievers = {
        document_id: retriever_factory(document.text)
        for document_id, document in documents.items()
    }
    actual_versions = {retriever.version for retriever in retrievers.values()}
    if len(actual_versions) != 1:
        raise ValueError("retriever factory returned inconsistent versions")
    actual_version = next(iter(actual_versions))
    if retriever_version is not None and retriever_version != actual_version:
        raise ValueError("declared retriever version does not match implementation")
    recalls: list[float] = []
    reciprocal_ranks: list[float] = []
    latencies_ms: list[float] = []
    false_positives = 0
    results: list[dict[str, Any]] = []

    for case in dataset.cases:
        document = documents[case.document_id]
        relevant_spans = {
            span.span_id: span
            for span in document.spans
            if span.span_id in case.relevant_span_ids
        }
        started = time.perf_counter()
        evidence = retrievers[case.document_id].search(case.query, top_k)
        latency_ms = (time.perf_counter() - started) * 1_000
        latencies_ms.append(latency_ms)

        retrieved_rows: list[dict[str, Any]] = []
        retrieved_relevant_span_ids: set[str] = set()
        first_relevant_rank: int | None = None
        for rank, item in enumerate(evidence, start=1):
            if item.source_start is None or item.source_end is None:
                raise ValueError("retriever evidence must include source offsets")
            matched = sorted(
                span_id
                for span_id, span in relevant_spans.items()
                if _overlaps(item.source_start, item.source_end, span)
            )
            if matched and first_relevant_rank is None:
                first_relevant_rank = rank
            retrieved_relevant_span_ids.update(matched)
            retrieved_rows.append(
                {
                    "rank": rank,
                    "evidenceId": item.evidence_id,
                    "score": round(item.score, 6),
                    "sourceStart": item.source_start,
                    "sourceEnd": item.source_end,
                    "matchedRelevantSpanIds": matched,
                }
            )

        if case.no_relevant_evidence:
            false_positive = bool(evidence)
            false_positives += int(false_positive)
            recall = None
            reciprocal_rank = None
        else:
            recall = len(retrieved_relevant_span_ids) / len(relevant_spans)
            reciprocal_rank = 0.0 if first_relevant_rank is None else 1 / first_relevant_rank
            recalls.append(recall)
            reciprocal_ranks.append(reciprocal_rank)
            false_positive = None

        results.append(
            {
                "id": case.case_id,
                "documentId": case.document_id,
                "tags": case.tags,
                "retrieved": retrieved_rows,
                "recall": None if recall is None else round(recall, 6),
                "reciprocalRank": (
                    None if reciprocal_rank is None else round(reciprocal_rank, 6)
                ),
                "falsePositive": false_positive,
            }
        )

    positive_cases = len(recalls)
    no_evidence_cases = len(dataset.cases) - positive_cases
    return {
        "schemaVersion": "retrieval-eval-result-v1",
        "datasetVersion": dataset.dataset_version,
        "datasetSha256": dataset_sha256,
        "retriever": {
            "name": retriever_name,
            "version": actual_version,
            "topK": top_k,
            "chunkSize": DEFAULT_CHUNK_SIZE,
            "chunkOverlap": DEFAULT_CHUNK_OVERLAP,
            **(
                retriever_parameters
                if retriever_parameters is not None
                else {
                    "dimensions": DEFAULT_HASHING_DIMENSIONS,
                    "minimumRelevanceScore": MIN_RELEVANCE_SCORE,
                }
            ),
        },
        "metrics": {
            "cases": len(dataset.cases),
            "positiveCases": positive_cases,
            "noEvidenceCases": no_evidence_cases,
            f"recallAt{top_k}": round(sum(recalls) / positive_cases, 6),
            f"mrrAt{top_k}": round(sum(reciprocal_ranks) / positive_cases, 6),
            "falsePositiveRate": round(
                false_positives / no_evidence_cases if no_evidence_cases else 0.0,
                6,
            ),
            "falsePositiveCases": false_positives,
            "retrievalP95Ms": round(_percentile_95(latencies_ms) or 0.0, 6),
        },
        "results": results,
    }

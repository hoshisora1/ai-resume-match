from __future__ import annotations

import hashlib
import math
import re
from dataclasses import dataclass
from threading import Event
from typing import Protocol


TOKEN_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9.+#_-]*")
HAN_SEQUENCE_PATTERN = re.compile(r"[\u4e00-\u9fff]+")
MIN_RELEVANCE_SCORE = 0.08
DEFAULT_CHUNK_SIZE = 600
DEFAULT_CHUNK_OVERLAP = 100
DEFAULT_HASHING_DIMENSIONS = 256
HASHING_RETRIEVER_VERSION = "hashing-blake2b-256-v1"
DENSE_RETRIEVER_VERSION = "dense-cosine-v1"
HYBRID_RETRIEVER_VERSION = "hybrid-rrf-v1"
DEFAULT_DENSE_MIN_SIMILARITY = 0.35
DEFAULT_RRF_K = 60
STOP_WORDS = {
    "a",
    "an",
    "and",
    "for",
    "in",
    "need",
    "of",
    "or",
    "the",
    "to",
    "with",
}


@dataclass(frozen=True, slots=True)
class Evidence:
    evidence_id: str
    excerpt: str
    score: float
    source_start: int | None = None
    source_end: int | None = None


@dataclass(frozen=True, slots=True)
class TextChunk:
    text: str
    source_start: int
    source_end: int


class Retriever(Protocol):
    @property
    def version(self) -> str: ...

    def search(self, query: str, top_k: int) -> list[Evidence]: ...


class EmbeddingClient(Protocol):
    @property
    def version(self) -> str: ...

    def embed(self, texts: list[str]) -> list[list[float]]: ...


def literal_term_pattern(term: str) -> re.Pattern[str]:
    """Match a literal term without conflating ASCII technology-name substrings."""
    prefix = r"(?<![a-z0-9_+#])" if term[0].isascii() else ""
    suffix = r"(?![a-z0-9_+#])" if term[-1].isascii() else ""
    return re.compile(prefix + re.escape(term) + suffix, re.IGNORECASE)


def lexical_tokens(text: str) -> list[str]:
    """Tokenize English/technology terms and overlapping Chinese bigrams.

    This public helper intentionally matches the hashing baseline tokenizer so
    deterministic verification and retrieval use the same lexical vocabulary.
    """

    folded = text.casefold()
    tokens: list[str] = []
    for raw_token in TOKEN_PATTERN.findall(folded):
        token = raw_token.strip("._-")
        if token and token not in STOP_WORDS:
            tokens.append(token)
    for sequence in HAN_SEQUENCE_PATTERN.findall(folded):
        if len(sequence) == 1:
            tokens.append(sequence)
        else:
            tokens.extend(sequence[index : index + 2] for index in range(len(sequence) - 1))
    return tokens


def normalize_text(text: str) -> str:
    """Return the canonical source representation used by chunk offsets."""

    return re.sub(r"\r\n?", "\n", text).strip()


def _trimmed_bounds(text: str, start: int, end: int) -> tuple[int, int]:
    while start < end and text[start].isspace():
        start += 1
    while end > start and text[end - 1].isspace():
        end -= 1
    return start, end


def chunk_text_with_offsets(
    text: str,
    chunk_size: int = DEFAULT_CHUNK_SIZE,
    overlap: int = DEFAULT_CHUNK_OVERLAP,
) -> list[TextChunk]:
    if chunk_size <= 0:
        raise ValueError("chunk_size must be positive")
    if overlap < 0 or overlap >= chunk_size:
        raise ValueError("overlap must be non-negative and less than chunk_size")

    normalized = normalize_text(text)
    if not normalized:
        return []

    chunks: list[TextChunk] = []
    paragraph_matches = list(re.finditer(r".+?(?=\n{2,}|\Z)", normalized, re.DOTALL))
    for match in paragraph_matches:
        paragraph_start, paragraph_end = _trimmed_bounds(
            normalized,
            match.start(),
            match.end(),
        )
        if paragraph_start == paragraph_end:
            continue
        if paragraph_end - paragraph_start <= chunk_size:
            chunks.append(
                TextChunk(
                    normalized[paragraph_start:paragraph_end],
                    paragraph_start,
                    paragraph_end,
                )
            )
            continue

        start = paragraph_start
        while start < paragraph_end:
            end = min(paragraph_end, start + chunk_size)
            chunk_start, chunk_end = _trimmed_bounds(normalized, start, end)
            if chunk_start < chunk_end:
                chunks.append(
                    TextChunk(
                        normalized[chunk_start:chunk_end],
                        chunk_start,
                        chunk_end,
                    )
                )
            if end == paragraph_end:
                break
            start = end - overlap
    return chunks


def chunk_text(
    text: str,
    chunk_size: int = DEFAULT_CHUNK_SIZE,
    overlap: int = DEFAULT_CHUNK_OVERLAP,
) -> list[str]:
    return [chunk.text for chunk in chunk_text_with_offsets(text, chunk_size, overlap)]


class HashingRetriever:
    def __init__(
        self,
        text: str,
        dimensions: int = DEFAULT_HASHING_DIMENSIONS,
        min_relevance_score: float = MIN_RELEVANCE_SCORE,
    ) -> None:
        if dimensions < 32:
            raise ValueError("dimensions must be at least 32")
        if not 0 <= min_relevance_score <= 1:
            raise ValueError("min_relevance_score must be between 0 and 1")
        self._dimensions = dimensions
        self._min_relevance_score = min_relevance_score
        self._entries = [
            (
                f"resume:{index}",
                chunk,
                self._embed(chunk.text),
                self._terms(chunk.text),
            )
            for index, chunk in enumerate(chunk_text_with_offsets(text))
        ]

    @property
    def version(self) -> str:
        return HASHING_RETRIEVER_VERSION

    def search(self, query: str, top_k: int) -> list[Evidence]:
        if top_k < 1:
            raise ValueError("top_k must be positive")
        query_vector = self._embed(query)
        query_terms = self._terms(query)
        if not query_terms or not any(query_vector):
            return []

        ranked: list[Evidence] = []
        for evidence_id, chunk, vector, terms in self._entries:
            # The exact-term guard prevents hashing collisions from creating evidence.
            # The cosine threshold also avoids returning a large, barely related chunk
            # merely because it contains one generic query token.
            if not query_terms.intersection(terms):
                continue
            score = self._cosine(query_vector, vector)
            if score < self._min_relevance_score:
                continue
            ranked.append(
                Evidence(
                    evidence_id,
                    chunk.text[:900],
                    score,
                    chunk.source_start,
                    chunk.source_end,
                )
            )
        ranked.sort(key=lambda item: (-item.score, item.evidence_id))
        return ranked[:top_k]

    def _embed(self, text: str) -> list[float]:
        vector = [0.0] * self._dimensions
        for token in self._tokens(text):
            digest = hashlib.blake2b(token.encode("utf-8"), digest_size=8).digest()
            index = int.from_bytes(digest, "big") % self._dimensions
            vector[index] += 1.0
        norm = math.sqrt(sum(value * value for value in vector))
        return vector if norm == 0 else [value / norm for value in vector]

    @classmethod
    def _terms(cls, text: str) -> set[str]:
        return set(cls._tokens(text))

    @staticmethod
    def _tokens(text: str) -> list[str]:
        return lexical_tokens(text)

    @staticmethod
    def _cosine(left: list[float], right: list[float]) -> float:
        return sum(a * b for a, b in zip(left, right))


def _normalize_vector(vector: list[float]) -> list[float]:
    if not vector or any(not math.isfinite(value) for value in vector):
        raise ValueError("embedding vector must contain finite values")
    norm = math.sqrt(sum(value * value for value in vector))
    if norm == 0:
        raise ValueError("embedding vector must not be zero")
    return [value / norm for value in vector]


class DenseRetriever:
    """Cosine retriever backed by a real, externally supplied embedding client.

    Chunk embeddings and repeated query embeddings are cached only for the lifetime
    of this retriever, which is one analysis request in the Agent service.
    """

    def __init__(
        self,
        text: str,
        embedding_client: EmbeddingClient,
        min_similarity: float = DEFAULT_DENSE_MIN_SIMILARITY,
    ) -> None:
        if not 0 <= min_similarity <= 1:
            raise ValueError("min_similarity must be between 0 and 1")
        self._embedding_client = embedding_client
        self._min_similarity = min_similarity
        self._chunks = chunk_text_with_offsets(text)
        self._chunk_vectors: list[list[float]] | None = None
        self._query_vectors: dict[str, list[float]] = {}
        self._cancelled = Event()

    @property
    def version(self) -> str:
        return f"{DENSE_RETRIEVER_VERSION}+{self._embedding_client.version}"

    def search(self, query: str, top_k: int) -> list[Evidence]:
        if top_k < 1:
            raise ValueError("top_k must be positive")
        normalized_query = query.strip()
        if not normalized_query or not self._chunks:
            return []
        self._ensure_chunk_vectors()
        query_vector = self._query_vectors.get(normalized_query)
        if query_vector is None:
            vectors = self._embed([normalized_query])
            if len(vectors) != 1:
                raise ValueError("embedding client returned an unexpected query vector count")
            query_vector = _normalize_vector(vectors[0])
            self._query_vectors[normalized_query] = query_vector

        if self._chunk_vectors is None:
            raise RuntimeError("dense index was not initialized")
        ranked: list[Evidence] = []
        for index, (chunk, vector) in enumerate(zip(self._chunks, self._chunk_vectors)):
            if len(query_vector) != len(vector):
                raise ValueError("query and document embedding dimensions differ")
            score = sum(left * right for left, right in zip(query_vector, vector))
            if score < self._min_similarity:
                continue
            ranked.append(
                Evidence(
                    evidence_id=f"resume:{index}",
                    excerpt=chunk.text[:900],
                    score=score,
                    source_start=chunk.source_start,
                    source_end=chunk.source_end,
                )
            )
        ranked.sort(key=lambda item: (-item.score, item.evidence_id))
        return ranked[:top_k]

    def _ensure_chunk_vectors(self) -> None:
        if self._chunk_vectors is not None:
            return
        vectors = self._embed([chunk.text for chunk in self._chunks])
        if len(vectors) != len(self._chunks):
            raise ValueError("embedding client returned an unexpected document vector count")
        normalized = [_normalize_vector(vector) for vector in vectors]
        dimensions = {len(vector) for vector in normalized}
        if len(dimensions) > 1:
            raise ValueError("document embedding dimensions differ")
        self._chunk_vectors = normalized

    def _embed(self, texts: list[str]) -> list[list[float]]:
        cancellable = getattr(self._embedding_client, "embed_cancellable", None)
        if cancellable is not None:
            return cancellable(texts, self._cancelled)
        if self._cancelled.is_set():
            raise RuntimeError("retrieval was cancelled")
        return self._embedding_client.embed(texts)

    def cancel(self) -> None:
        self._cancelled.set()


class HybridRetriever:
    """Fuse lexical and dense rankings using normalized reciprocal-rank fusion."""

    def __init__(
        self,
        text: str,
        embedding_client: EmbeddingClient,
        *,
        dense_min_similarity: float = DEFAULT_DENSE_MIN_SIMILARITY,
        lexical_weight: float = 0.45,
        dense_weight: float = 0.55,
        rrf_k: int = DEFAULT_RRF_K,
    ) -> None:
        if lexical_weight < 0 or dense_weight < 0 or lexical_weight + dense_weight <= 0:
            raise ValueError("retrieval weights must be non-negative with a positive sum")
        if rrf_k < 1:
            raise ValueError("rrf_k must be positive")
        weight_total = lexical_weight + dense_weight
        self._lexical_weight = lexical_weight / weight_total
        self._dense_weight = dense_weight / weight_total
        self._rrf_k = rrf_k
        self._lexical = HashingRetriever(text)
        self._dense = DenseRetriever(
            text,
            embedding_client,
            min_similarity=dense_min_similarity,
        )

    @property
    def version(self) -> str:
        return f"{HYBRID_RETRIEVER_VERSION}+{self._dense.version}"

    def search(self, query: str, top_k: int) -> list[Evidence]:
        if top_k < 1:
            raise ValueError("top_k must be positive")
        candidate_k = max(10, top_k * 3)
        branches = (
            (self._lexical.search(query, candidate_k), self._lexical_weight),
            (self._dense.search(query, candidate_k), self._dense_weight),
        )
        evidence_by_id: dict[str, Evidence] = {}
        fused_scores: dict[str, float] = {}
        for evidence, weight in branches:
            for rank, item in enumerate(evidence, start=1):
                evidence_by_id.setdefault(item.evidence_id, item)
                fused_scores[item.evidence_id] = fused_scores.get(item.evidence_id, 0.0) + (
                    weight / (self._rrf_k + rank)
                )

        maximum_score = 1 / (self._rrf_k + 1)
        ranked = [
            Evidence(
                evidence_id=evidence_id,
                excerpt=evidence_by_id[evidence_id].excerpt,
                score=min(1.0, score / maximum_score),
                source_start=evidence_by_id[evidence_id].source_start,
                source_end=evidence_by_id[evidence_id].source_end,
            )
            for evidence_id, score in fused_scores.items()
        ]
        ranked.sort(key=lambda item: (-item.score, item.evidence_id))
        return ranked[:top_k]

    def cancel(self) -> None:
        self._dense.cancel()

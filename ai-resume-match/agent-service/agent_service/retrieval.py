from __future__ import annotations

import hashlib
import math
import re
from dataclasses import dataclass


TOKEN_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9.+#_-]*")
HAN_SEQUENCE_PATTERN = re.compile(r"[\u4e00-\u9fff]+")
MIN_RELEVANCE_SCORE = 0.08
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


def chunk_text(text: str, chunk_size: int = 600, overlap: int = 100) -> list[str]:
    normalized = re.sub(r"\r\n?", "\n", text).strip()
    paragraphs = [part.strip() for part in re.split(r"\n{2,}", normalized) if part.strip()]
    chunks: list[str] = []
    for paragraph in paragraphs or [normalized]:
        if len(paragraph) <= chunk_size:
            chunks.append(paragraph)
            continue
        start = 0
        while start < len(paragraph):
            end = min(len(paragraph), start + chunk_size)
            chunks.append(paragraph[start:end].strip())
            if end == len(paragraph):
                break
            start = end - overlap
    return [chunk for chunk in chunks if chunk]


class HashingRetriever:
    def __init__(
        self,
        text: str,
        dimensions: int = 256,
        min_relevance_score: float = MIN_RELEVANCE_SCORE,
    ) -> None:
        if dimensions < 32:
            raise ValueError("dimensions must be at least 32")
        if not 0 <= min_relevance_score <= 1:
            raise ValueError("min_relevance_score must be between 0 and 1")
        self._dimensions = dimensions
        self._min_relevance_score = min_relevance_score
        self._entries = [
            (f"resume:{index}", chunk, self._embed(chunk), self._terms(chunk))
            for index, chunk in enumerate(chunk_text(text))
        ]

    def search(self, query: str, top_k: int) -> list[Evidence]:
        query_vector = self._embed(query)
        query_terms = self._terms(query)
        if not query_terms or not any(query_vector):
            return []

        ranked: list[Evidence] = []
        for evidence_id, excerpt, vector, terms in self._entries:
            # The exact-term guard prevents hashing collisions from creating evidence.
            # The cosine threshold also avoids returning a large, barely related chunk
            # merely because it contains one generic query token.
            if not query_terms.intersection(terms):
                continue
            score = self._cosine(query_vector, vector)
            if score < self._min_relevance_score:
                continue
            ranked.append(Evidence(evidence_id, excerpt[:900], score))
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
        folded = text.casefold()
        tokens: list[str] = []
        for raw_token in TOKEN_PATTERN.findall(folded):
            # Keep meaningful internal technology punctuation (for example node.js,
            # c++ and c#) while dropping sentence punctuation captured at an edge.
            token = raw_token.strip("._-")
            if token and token not in STOP_WORDS:
                tokens.append(token)
        for sequence in HAN_SEQUENCE_PATTERN.findall(folded):
            if len(sequence) == 1:
                tokens.append(sequence)
            else:
                tokens.extend(sequence[index : index + 2] for index in range(len(sequence) - 1))
        return tokens

    @staticmethod
    def _cosine(left: list[float], right: list[float]) -> float:
        return sum(a * b for a, b in zip(left, right))

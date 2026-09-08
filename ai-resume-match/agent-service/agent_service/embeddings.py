from __future__ import annotations

import math
import re
from threading import Event
from typing import Any

import httpx

from agent_service.tracing import AgentTracing


class EmbeddingResponseError(RuntimeError):
    pass


class EmbeddingCancelledError(RuntimeError):
    pass


class OpenAICompatibleEmbeddingClient:
    """Small synchronous adapter for OpenAI-compatible embedding endpoints.

    Calls run in the Agent's worker thread so provider I/O does not block the
    FastAPI event loop. Provider response bodies are never included in errors.
    """

    def __init__(
        self,
        *,
        endpoint: str,
        api_key: str,
        model: str,
        timeout_seconds: float,
        batch_size: int = 64,
        transport: httpx.BaseTransport | None = None,
        tracing: AgentTracing | None = None,
    ) -> None:
        if not endpoint.strip():
            raise ValueError("embedding endpoint must not be blank")
        if not api_key.strip():
            raise ValueError("embedding API key must not be blank")
        if not model.strip():
            raise ValueError("embedding model must not be blank")
        if not 1 <= timeout_seconds <= 120:
            raise ValueError("embedding timeout must be between 1 and 120 seconds")
        if not 1 <= batch_size <= 128:
            raise ValueError("embedding batch size must be between 1 and 128")
        self._endpoint = endpoint
        self._model = model
        self._batch_size = batch_size
        self._client = httpx.Client(
            timeout=timeout_seconds,
            transport=transport,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
            },
        )
        if tracing is not None:
            tracing.instrument_httpx_client(self._client)

    @property
    def version(self) -> str:
        safe_model = re.sub(r"[^A-Za-z0-9._-]+", "-", self._model).strip("-")
        return f"openai-compatible-{safe_model[:60]}-v1"

    def embed(self, texts: list[str]) -> list[list[float]]:
        return self.embed_cancellable(texts, Event())

    def embed_cancellable(
        self,
        texts: list[str],
        cancelled: Event,
    ) -> list[list[float]]:
        if not texts:
            return []
        if any(not isinstance(text, str) or not text.strip() for text in texts):
            raise ValueError("embedding inputs must be non-blank strings")
        vectors: list[list[float]] = []
        for start in range(0, len(texts), self._batch_size):
            if cancelled.is_set():
                raise EmbeddingCancelledError("embedding request was cancelled")
            batch = texts[start : start + self._batch_size]
            response = self._client.post(
                self._endpoint,
                json={"model": self._model, "input": batch, "encoding_format": "float"},
            )
            response.raise_for_status()
            vectors.extend(self._parse_vectors(response, len(batch)))
        dimensions = {len(vector) for vector in vectors}
        if len(dimensions) != 1:
            raise EmbeddingResponseError("embedding provider returned inconsistent dimensions")
        return vectors

    @staticmethod
    def _parse_vectors(response: httpx.Response, expected: int) -> list[list[float]]:
        try:
            payload: Any = response.json()
            rows = payload["data"]
            if not isinstance(rows, list) or len(rows) != expected:
                raise ValueError
            ordered = sorted(rows, key=lambda row: row["index"])
            if [row["index"] for row in ordered] != list(range(expected)):
                raise ValueError
            vectors = [row["embedding"] for row in ordered]
            if any(
                not isinstance(vector, list)
                or not vector
                or any(
                    isinstance(value, bool)
                    or not isinstance(value, (int, float))
                    or not math.isfinite(float(value))
                    for value in vector
                )
                for vector in vectors
            ):
                raise ValueError
            converted = [[float(value) for value in vector] for vector in vectors]
            if any(not any(value != 0 for value in vector) for vector in converted):
                raise ValueError
            return converted
        except (KeyError, TypeError, ValueError) as exc:
            raise EmbeddingResponseError(
                "embedding provider returned an invalid response"
            ) from exc

    def close(self) -> None:
        self._client.close()

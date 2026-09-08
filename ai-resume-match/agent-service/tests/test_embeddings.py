import json
from threading import Event

import httpx
import pytest

from agent_service.embeddings import (
    EmbeddingCancelledError,
    EmbeddingResponseError,
    OpenAICompatibleEmbeddingClient,
)


def test_batches_and_reorders_openai_compatible_embedding_response() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        payload = json.loads(request.content)
        rows = [
            {"index": index, "embedding": [float(len(text)), 1.0]}
            for index, text in enumerate(payload["input"])
        ]
        return httpx.Response(200, json={"data": list(reversed(rows))})

    client = OpenAICompatibleEmbeddingClient(
        endpoint="https://embedding.test/v1/embeddings",
        api_key="secret-key",
        model="multilingual-model",
        timeout_seconds=5,
        batch_size=2,
        transport=httpx.MockTransport(handler),
    )
    try:
        vectors = client.embed(["one", "three", "seven"])
    finally:
        client.close()

    assert vectors == [[3.0, 1.0], [5.0, 1.0], [5.0, 1.0]]
    assert len(requests) == 2
    assert requests[0].headers["Authorization"] == "Bearer secret-key"
    assert client.version == "openai-compatible-multilingual-model-v1"


@pytest.mark.parametrize(
    "payload",
    [
        {},
        {"data": []},
        {"data": [{"index": 1, "embedding": [1.0]}]},
        {"data": [{"index": 0, "embedding": [True]}]},
        {"data": [{"index": 0, "embedding": [0.0, 0.0]}]},
        {"data": [{"index": 0, "embedding": [float("nan")]}]},
    ],
)
def test_rejects_invalid_embedding_provider_payload_without_leaking_it(payload: dict) -> None:
    client = OpenAICompatibleEmbeddingClient(
        endpoint="https://embedding.test/v1/embeddings",
        api_key="secret-key",
        model="model",
        timeout_seconds=5,
        transport=httpx.MockTransport(
            lambda _: httpx.Response(
                200,
                content=json.dumps(payload).encode(),
                headers={"Content-Type": "application/json"},
            )
        ),
    )
    try:
        with pytest.raises(EmbeddingResponseError) as captured:
            client.embed(["query"])
    finally:
        client.close()

    assert "secret-key" not in str(captured.value)
    assert repr(payload) not in str(captured.value)


def test_rejects_inconsistent_embedding_dimensions() -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "data": [
                    {"index": 0, "embedding": [1.0, 0.0]},
                    {"index": 1, "embedding": [1.0]},
                ]
            },
        )

    client = OpenAICompatibleEmbeddingClient(
        endpoint="https://embedding.test/v1/embeddings",
        api_key="secret-key",
        model="model",
        timeout_seconds=5,
        transport=httpx.MockTransport(handler),
    )
    try:
        with pytest.raises(EmbeddingResponseError, match="inconsistent dimensions"):
            client.embed(["one", "two"])
    finally:
        client.close()


def test_cancellation_stops_before_the_next_embedding_batch() -> None:
    cancelled = Event()
    calls = 0

    def handler(_: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        cancelled.set()
        return httpx.Response(
            200,
            json={"data": [{"index": 0, "embedding": [1.0, 0.0]}]},
        )

    client = OpenAICompatibleEmbeddingClient(
        endpoint="https://embedding.test/v1/embeddings",
        api_key="secret-key",
        model="model",
        timeout_seconds=5,
        batch_size=1,
        transport=httpx.MockTransport(handler),
    )
    try:
        with pytest.raises(EmbeddingCancelledError):
            client.embed_cancellable(["first", "second"], cancelled)
    finally:
        client.close()

    assert calls == 1

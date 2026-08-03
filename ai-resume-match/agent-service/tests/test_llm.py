import asyncio
import json

import httpx
import pytest

from agent_service.llm import ModelResponseError, OpenAICompatibleChatModel


def test_parses_tool_calls_and_reuses_openai_compatible_contract() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"] == "Bearer test-key"
        body = json.loads(request.content)
        assert body["model"] == "test-model"
        assert body["tool_choice"] == "auto"
        assert body["temperature"] == 0
        assert body["max_completion_tokens"] == 777
        assert body["tools"][0]["function"]["name"] == "get_job_requirements"
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": None,
                            "tool_calls": [
                                {
                                    "id": "call-1",
                                    "type": "function",
                                    "function": {
                                        "name": "get_job_requirements",
                                        "arguments": "{}",
                                    },
                                }
                            ],
                        }
                    }
                ],
                "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 5,
                    "total_tokens": 15,
                },
            },
        )

    async def scenario():
        model = OpenAICompatibleChatModel(
            endpoint="https://model.test/v1/chat/completions",
            api_key="test-key",
            model="test-model",
            timeout_seconds=5,
            max_completion_tokens=777,
            transport=httpx.MockTransport(handler),
        )
        try:
            return await model.complete(
                [{"role": "system", "content": "bounded agent"}],
                [
                    {
                        "type": "function",
                        "function": {
                            "name": "get_job_requirements",
                            "parameters": {"type": "object"},
                        },
                    }
                ],
            )
        finally:
            await model.aclose()

    turn = asyncio.run(scenario())

    assert turn.tool_calls[0].name == "get_job_requirements"
    assert turn.usage.total_tokens == 15
    assert turn.usage.provider_reported is True


def test_accepts_missing_provider_usage_without_claiming_reported_totals() -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": None,
                            "tool_calls": [
                                {
                                    "id": "call-1",
                                    "type": "function",
                                    "function": {
                                        "name": "get_job_requirements",
                                        "arguments": "{}",
                                    },
                                }
                            ],
                        }
                    }
                ]
            },
        )

    async def scenario():
        model = OpenAICompatibleChatModel(
            endpoint="https://model.test/v1/chat/completions",
            api_key="test-key",
            model="test-model",
            timeout_seconds=5,
            transport=httpx.MockTransport(handler),
        )
        try:
            return await model.complete([], [])
        finally:
            await model.aclose()

    turn = asyncio.run(scenario())

    assert turn.usage.total_tokens == 0
    assert turn.usage.provider_reported is False


def test_rejects_invalid_completion_token_limit() -> None:
    with pytest.raises(ValueError, match="max_completion_tokens"):
        OpenAICompatibleChatModel(
            endpoint="https://model.test/v1/chat/completions",
            api_key="test-key",
            model="test-model",
            timeout_seconds=5,
            max_completion_tokens=0,
        )


def test_rejects_non_json_model_response() -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text="not-json")

    async def scenario() -> None:
        model = OpenAICompatibleChatModel(
            endpoint="https://model.test/v1/chat/completions",
            api_key="test-key",
            model="test-model",
            timeout_seconds=5,
            transport=httpx.MockTransport(handler),
        )
        try:
            with pytest.raises(ModelResponseError, match="invalid OpenAI-compatible response"):
                await model.complete([], [])
        finally:
            await model.aclose()

    asyncio.run(scenario())
